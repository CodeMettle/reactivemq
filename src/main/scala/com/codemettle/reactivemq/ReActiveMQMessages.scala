/*
 * ReActiveMQMessages.scala
 *
 * Updated: Jan 28, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

import com.codemettle.reactivemq.model.{Destination, AMQMessage}

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
    case object SubscribeToConnections

    sealed trait ConnectionStatusMessage {
        def connectionActor: ActorRef
    }

    @SerialVersionUID(1L)
    case class ConnectionInterrupted(connectionActor: ActorRef) extends ConnectionStatusMessage

    @SerialVersionUID(1L)
    case class ConnectionReestablished(connectionActor: ActorRef) extends ConnectionStatusMessage

    sealed trait ConnectedOperation {
        def timeout: FiniteDuration
    }

    @SerialVersionUID(1L)
    case class SendMessage(to: Destination, message: AMQMessage, timeout: FiniteDuration = 10.seconds)
        extends ConnectedOperation
}
