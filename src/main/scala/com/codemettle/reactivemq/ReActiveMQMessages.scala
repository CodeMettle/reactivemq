/*
 * ReActiveMQMessages.scala
 *
 * Updated: Feb 19, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

import com.codemettle.reactivemq.config.{AutoConnectConfig, CredentialsDeobfuscator}
import com.codemettle.reactivemq.model.{AMQMessage, Destination, Queue, Topic}

import akka.actor.ActorRef
import scala.concurrent.duration._

/**
 * @author steven
 *
 */
object ReActiveMQMessages {
    sealed trait ConnectionRequest {
        def timeout: FiniteDuration
        def staticActorName: Option[String]
    }

    private[reactivemq] case class AutoConnect(config: AutoConnectConfig, connName: String,
                                               credsDeobfuscator: CredentialsDeobfuscator, timeout: FiniteDuration)
        extends ConnectionRequest {
        override def staticActorName: Option[String] = Some(connName)
    }

    @SerialVersionUID(1L)
    case class GetConnection(brokerUrl: String, staticActorName: Option[String] = None,
                             timeout: FiniteDuration = 10.seconds) extends ConnectionRequest

    @SerialVersionUID(1L)
    case class GetAuthenticatedConnection(brokerUrl: String, username: String, password: String,
                                          staticActorName: Option[String] = None, timeout: FiniteDuration = 10.seconds)
        extends ConnectionRequest

    sealed trait ConnectionResponse {
        def request: ConnectionRequest
    }

    @SerialVersionUID(1L)
    case class ConnectionEstablished(request: ConnectionRequest, connectionActor: ActorRef) extends ConnectionResponse

    @SerialVersionUID(1L)
    case class ConnectionFailed(request: ConnectionRequest, cause: Throwable) extends ConnectionResponse

    @SerialVersionUID(1L)
    case object CloseConnection

    @SerialVersionUID(1L)
    case object SubscribeToConnectionStatus

    sealed trait ConnectionStatusMessage {
        def connectionActor: ActorRef
    }

    /**
     * @param initialStateNotification set to true on the message sent after first subscribing to notifications
     */
    @SerialVersionUID(1L)
    case class ConnectionInterrupted(connectionActor: ActorRef, initialStateNotification: Boolean) extends ConnectionStatusMessage

    /**
     * @param initialStateNotification set to true on the message sent after first subscribing to notifications
     */
    @SerialVersionUID(1L)
    case class ConnectionReestablished(connectionActor: ActorRef, initialStateNotification: Boolean) extends ConnectionStatusMessage

    sealed trait ConnectedOperation {
        def timeout: FiniteDuration
    }

    @SerialVersionUID(1L)
    case class SendMessage(to: Destination, message: AMQMessage, timeToLive: Long = 0,
                           timeout: FiniteDuration = 10.seconds) extends ConnectedOperation

    @SerialVersionUID(1L)
    case object SendAck

    @SerialVersionUID(1L)
    case class RequestMessage(to: Destination, message: AMQMessage, timeout: FiniteDuration = 20.seconds)
        extends ConnectedOperation

    sealed trait ConsumerMessage extends Equals {
        def destination: Destination
        def sharedConsumer: Boolean

        override def canEqual(that: Any): Boolean = that.isInstanceOf[ConsumerMessage]

        override def equals(obj: scala.Any): Boolean = obj match {
            case that: ConsumerMessage ⇒ (that canEqual this) && this.destination == that.destination &&
                this.sharedConsumer == that.sharedConsumer

            case _ ⇒ false
        }
    }

    @SerialVersionUID(1L)
    case class ConsumeFromTopic(name: String, sharedConsumer: Boolean = true) extends ConsumerMessage {
        @transient lazy val destination = Topic(name)
    }

    @SerialVersionUID(1L)
    case class ConsumeFromQueue(name: String, sharedConsumer: Boolean = false) extends ConsumerMessage {
        @transient lazy val destination = Queue(name)
    }

    @SerialVersionUID(1L)
    case class Consume(destination: Destination, sharedConsumer: Boolean) extends ConsumerMessage

    // non-shared consumers
    sealed trait DedicatedConsumerNotif {
        def destination: Destination
    }

    @SerialVersionUID(1L)
    case class ConsumeFailed(destination: Destination, error: Throwable) extends DedicatedConsumerNotif

    @SerialVersionUID(1L)
    case class ConsumeSuccess(destination: Destination) extends DedicatedConsumerNotif

    @SerialVersionUID(1L)
    case class EndConsumption(destination: Destination)

    @SerialVersionUID(1L)
    case class ConsumptionEnded(destination: Destination)
    // end non-shared consumers
}
