/*
 * Producer.scala
 *
 * Updated: Feb 11, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

import com.codemettle.reactivemq.Producer.{ResponseTransformer, LogError}
import com.codemettle.reactivemq.ReActiveMQMessages.{RequestMessage, SendMessage}
import com.codemettle.reactivemq.model.{AMQMessage, Destination}

import akka.actor._
import akka.pattern.ask
import akka.util.{Helpers, Timeout}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * @author steven
 *
 */
object Producer {
    private case class LogError(t: Throwable, msg: AMQMessage)

    private case object TimedOut

    private class ResponseTransformer(timeout: FiniteDuration, origSender: ActorRef, xform: (Any) ⇒ Any) extends Actor {
        import context.dispatcher

        private val timer = context.system.scheduler.scheduleOnce(timeout + 1.second, self, TimedOut)

        override def postStop() = {
            super.postStop()

            timer.cancel()
        }

        def receive = {
            case TimedOut ⇒ context stop self

            case msg ⇒ Try(xform(msg)) match {
                case Success(m) ⇒ origSender forward m
                case Failure(t) ⇒ origSender forward Status.Failure(t)
            }
        }
    }

    private object ResponseTransformer {
        def props(timeout: FiniteDuration, origSender: ActorRef, xform: (Any) ⇒ Any) = {
            Props(new ResponseTransformer(timeout, origSender, xform))
        }
    }
}

trait Producer extends Actor with TwoWayCapable with ActorLogging {
    import context.dispatcher

    def connection: ActorRef
    def destination: Destination

    private val actorNamer = Iterator from 0 map (i ⇒ s"responder${Helpers.base64(i)}")

    protected def transformOutgoingMessage(msg: Any) = msg

    protected def transformResponse(msg: Any) = msg

    /**
     * Override to false if you want the Unit/Status.Failure from a one-way message returned
     */
    protected def swallowSendStatus: Boolean = true

    /**
     * Timeout sent with each request if this is a request/reply (non-oneway) producer
     */
    protected def requestTimeout: FiniteDuration = 20.seconds

    /**
     * Message expiration sent with each message if this is a oneway producer
     */
    protected def sendTimeout: FiniteDuration = 10.seconds

    final def receive = {
        case LogError(t, msg) ⇒
            log.error(t, "Error sending {}", msg)

        case msg ⇒
            val newMsg = transformOutgoingMessage(msg) match {
                case amq: AMQMessage ⇒ amq
                case other ⇒ AMQMessage(other)
            }

            if (oneway) {
                val sendMessage = SendMessage(destination, newMsg, sendTimeout.toMillis, sendTimeout)

                if (swallowSendStatus) {
                    implicit val timeout = Timeout(sendTimeout + 5.seconds)
                    (connection ? sendMessage).failed.foreach(t ⇒ self ! LogError(t, newMsg))
                } else {
                    connection forward sendMessage
                }
            } else {
                val respActor = context
                    .actorOf(ResponseTransformer.props(requestTimeout, sender(), transformResponse), actorNamer.next())
                connection.tell(RequestMessage(destination, newMsg, requestTimeout), respActor)
            }
    }
}
