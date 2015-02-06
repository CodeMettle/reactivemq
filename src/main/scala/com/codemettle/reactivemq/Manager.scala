/*
 * Manager.scala
 *
 * Updated: Feb 6, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

import com.codemettle.reactivemq.ReActiveMQExtensionImpl.ConnectionFactoryHolder
import com.codemettle.reactivemq.ReActiveMQMessages.{AutoConnect, ConnectionRequest, GetAuthenticatedConnection, GetConnection}
import com.codemettle.reactivemq.activemq.ConnectionFactory.ConnectionKey
import com.codemettle.reactivemq.connection.ConnectionFactoryActor

import akka.actor._
import akka.util.Helpers.base64
import scala.util.{Failure, Success, Try}

/**
 * @author steven
 *
 */
object Manager {
    def props(connFactHolder: ConnectionFactoryHolder) = {
        Props(new Manager(connFactHolder))
    }
}

class Manager(connFactHolder: ConnectionFactoryHolder) extends Actor with ActorLogging {
    private var connectionFactories = Map.empty[ConnectionKey, ActorRef]

    private val connFactName = Iterator from 0 map (i ⇒ s"Connection${base64(i)}")

    private def getConnectionFact(key: ConnectionKey, staticName: Option[String]): Try[ActorRef] = Try {
        connectionFactories.getOrElse(key, {
            val connFact = connFactHolder getConnectionFactory key

            val name = staticName.fold(connFactName.next())(identity)

            log.debug("Creating ConnectionFactory to {} with name {}", key.brokerUrl, name)

            val act = context.actorOf(ConnectionFactoryActor.props(connFact), name)

            context watch act

            connectionFactories += (key → act)

            act
        })
    }

    private def openConnection(key: ConnectionKey, req: ConnectionRequest) = {
        getConnectionFact(key, req.staticActorName) match {
            case Success(connFact) ⇒ connFact forward req
            case Failure(t) ⇒ sender() ! Status.Failure(t)
        }
    }

    def receive = {
        case req@AutoConnect(brokerUrl, name) ⇒
            openConnection(ConnectionKey(brokerUrl, None, Some(name)), req)

        case req@GetConnection(brokerUrl, staticName, _) ⇒
            openConnection(ConnectionKey(brokerUrl, None, staticName), req)

        case req@GetAuthenticatedConnection(brokerUrl, user, pass, staticName, _) ⇒
            openConnection(ConnectionKey(brokerUrl, Some(user → pass), staticName), req)

        case Terminated(act) ⇒ connectionFactories find (_._2 == act) foreach (kv ⇒ {
            connFactHolder closeConnectionFactory kv._1
            connectionFactories -= kv._1
        })
    }
}
