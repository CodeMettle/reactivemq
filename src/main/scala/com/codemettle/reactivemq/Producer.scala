/*
 * Producer.scala
 *
 * Updated: Feb 11, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

import com.codemettle.reactivemq.Producer.LogError
import com.codemettle.reactivemq.ReActiveMQMessages.{RequestMessage, SendMessage}
import com.codemettle.reactivemq.model.{AMQMessage, Destination}

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._

/**
 * @author steven
 *
 */
object Producer {
    private case class LogError(t: Throwable, msg: AMQMessage)
}

trait Producer extends Actor with TwoWayCapable with ActorLogging {
    import context.dispatcher

    def connection: ActorRef
    def destination: Destination

    protected def transformOutgoingMessage(msg: Any) = msg

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
                    (connection ? sendMessage) onFailure {
                        case t ⇒ self ! LogError(t, newMsg)
                    }
                } else {
                    connection forward sendMessage
                }
            } else
                connection forward RequestMessage(destination, newMsg)
    }
}
