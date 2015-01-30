/*
 * RequestReplyActor.scala
 *
 * Updated: Jan 30, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.connection.requestreply

import java.util.UUID
import javax.jms.MessageProducer

import com.codemettle.reactivemq.ReActiveMQMessages.{RequestMessage, SendMessage}
import com.codemettle.reactivemq.RequestTimedOut
import com.codemettle.reactivemq.connection.SendRepliesAs
import com.codemettle.reactivemq.connection.requestreply.RequestReplyActor.TimedOut
import com.codemettle.reactivemq.model.{AMQMessage, TempQueue}

import akka.actor._
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import scala.concurrent.duration._

/**
 * @author steven
 *
 */
object RequestReplyActor {
    def props(replyTo: ActorRef, tempQueueManager: ActorRef, replyQueue: TempQueue, connection: ActorRef, sendRepliesAs: ActorRef) = {
        Props(new RequestReplyActor(replyTo, tempQueueManager, replyQueue, connection, sendRepliesAs))
    }

    private case class TimedOut(timeout: FiniteDuration)
}

class RequestReplyActor(replyTo: ActorRef, tempQueueManager: ActorRef, replyQueue: TempQueue, connection: ActorRef,
                        protected val sendRepliesAs: ActorRef) extends Actor with SendRepliesAs {
    import context.dispatcher

    private var timer = Option.empty[Cancellable]

    override def preStart() = {
        super.preStart()

        context setReceiveTimeout 1.minute
    }

    override def postStop() = {
        super.postStop()

        timer foreach (_.cancel())
    }

    def receive = {
        case ReceiveTimeout ⇒ context stop self

        case error: Status.Failure ⇒ replyTo tellFromSRA error

        case RequestMessage(dest, msg, timeout) ⇒
            val withReplyTo = msg.properties.copy(replyTo = Some(replyQueue))
            val withCorrelation = withReplyTo.correlationID
                .fold(withReplyTo.copy(correlationID = Some(UUID.randomUUID().toString)))(_ ⇒ withReplyTo)

            val toSend = msg.copy(properties = withCorrelation)

            tempQueueManager ! TempQueueReplyManager.RegisterListener(withCorrelation.correlationID.get, timeout)

            context setReceiveTimeout Duration.Undefined

            timer = Some(context.system.scheduler.scheduleOnce(timeout, self, TimedOut(timeout)))

            implicit val to = Timeout(timeout)

            (connection ? SendMessage(dest, toSend, timeout.toMillis, timeout)) onFailure {
                case _: AskTimeoutException ⇒
                    replyTo tellFromSRA Status.Failure(RequestTimedOut(timeout))
                    context stop self

                case t ⇒
                    replyTo tellFromSRA Status.Failure(t)
                    context stop self
            }

        case reply: AMQMessage ⇒
            replyTo tellFromSRA reply
            context stop self

        case TimedOut(timeout) ⇒
            replyTo tellFromSRA Status.Failure(RequestTimedOut(timeout))
            context stop self
    }
}
