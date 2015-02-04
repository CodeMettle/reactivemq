/*
 * QueueConsumer.scala
 *
 * Updated: Feb 4, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

import java.util.UUID

import com.codemettle.reactivemq.QueueConsumer.QueueConsumerSubscriber
import com.codemettle.reactivemq.ReActiveMQMessages.{Consume, ConsumeFailed, DedicatedConsumerNotif, SendMessage}
import com.codemettle.reactivemq.config.ReActiveMQConfig
import com.codemettle.reactivemq.model.{AMQMessage, Destination, Queue}

import akka.actor._
import scala.concurrent.duration._

/**
 * @author steven
 *
 */
object QueueConsumer {
    private class ResponderActor(connectionActor: ActorRef) extends Actor with ActorLogging {
        import context.dispatcher

        private var timer = Option.empty[Cancellable]

        private var replyToOrOrigMsg: Either[AMQMessage, Destination] = _
        private var correlationId = Option.empty[String]

        override def postStop() = {
            super.postStop()

            timer foreach (_.cancel())
        }

        private def sendResponseToOriginator(reply: AMQMessage) = {
            replyToOrOrigMsg match {
                case Left(msg) ⇒ log.warning("No JMSReplyTo on {}, can't reply with {}", msg, reply)
                case Right(replyTo) ⇒
                    val replyMsg = correlationId.fold(reply)(reply.withCorrelationID)

                    connectionActor ! SendMessage(replyTo, replyMsg)
            }

            context stop self
        }

        private def configureResponder(msgToReplyTo: AMQMessage, defaultTimeout: FiniteDuration) = {
            def timeUntilExpiration = {
                val now = System.currentTimeMillis()
                if (msgToReplyTo.properties.expiration > now)
                    (msgToReplyTo.properties.expiration - now).millis
                else
                    defaultTimeout
            }

            timer = Some(context.system.scheduler.scheduleOnce(timeUntilExpiration, self, Expired))

            correlationId = msgToReplyTo.properties.correlationID
            replyToOrOrigMsg = msgToReplyTo.properties.replyTo toRight msgToReplyTo
        }

        def receive = {
            case MessageForResponse(msg, defaultTimeout) ⇒ configureResponder(msg, defaultTimeout)
            case Expired ⇒ context stop self
            case message: AMQMessage ⇒ sendResponseToOriginator(message)
            case message ⇒ sendResponseToOriginator(AMQMessage(message))
        }
    }

    private object ResponderActor {
        def props(connectionActor: ActorRef) = {
            Props(new ResponderActor(connectionActor))
        }
    }

    private class QueueConsumerSubscriber(connectionActor: ActorRef, dest: Queue, sendStatusNotifs: Boolean,
                                          noExpirationReplyTimeout: FiniteDuration) extends Actor {
        override def preStart() = {
            super.preStart()

            subscribe()
        }

        private def subscribe() = {
            connectionActor ! Consume(dest, sharedConsumer = false)
        }

        private lazy val requestProps = ResponderActor props connectionActor

        private def createResponder(forMessage: AMQMessage): ActorRef = {
            val msgId = forMessage.properties.messageID getOrElse UUID.randomUUID().toString

            val actor = context.actorOf(requestProps, s"responder-$msgId")

            actor ! MessageForResponse(forMessage, noExpirationReplyTimeout)

            actor
        }

        def receive = {
            case cf: ConsumeFailed ⇒
                subscribe()
                if (sendStatusNotifs) context.parent forward cf

            case notif: DedicatedConsumerNotif ⇒ if (sendStatusNotifs) context.parent forward notif

            case msg: AMQMessage ⇒ context.parent.tell(msg, createResponder(msg))
        }
    }

    private object QueueConsumerSubscriber {
        def props(connectionActor: ActorRef, dest: Queue, sendStatusNotifs: Boolean,
                  noExpirationReplyTimeout: FiniteDuration) = {
            Props(new QueueConsumerSubscriber(connectionActor, dest, sendStatusNotifs, noExpirationReplyTimeout))
        }
    }

    private case class MessageForResponse(msg: AMQMessage, defaultTimeout: FiniteDuration)
    private case object Expired
}

trait QueueConsumer extends Actor {
    private val config = ReActiveMQConfig(context.system)

    context.actorOf(
        QueueConsumerSubscriber.props(connection, consumeFrom, receiveConsumeNotifications, noExpirationReplyTimeout),
        "sub")

    protected def connection: ActorRef
    protected def consumeFrom: Queue

    /**
     * Override in an individual consumer to ignore the configured default
     */
    protected def noExpirationReplyTimeout: FiniteDuration = config.queueConsumerTimeout

    /**
     * Override in an individual consumer to receive ConsumeSuccess and ConsumeFailed messages
     */
    protected def receiveConsumeNotifications: Boolean = false
}
