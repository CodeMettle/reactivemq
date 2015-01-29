/*
 * Manager.scala
 *
 * Updated: Jan 28, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

import org.apache.activemq.ActiveMQConnectionFactory

import com.codemettle.reactivemq.Manager.ConnectionKey
import com.codemettle.reactivemq.ReActiveMQMessages.{ConnectionRequest, GetAuthenticatedConnection, GetConnection}
import com.codemettle.reactivemq.connection.ConnectionFactoryActor

import akka.actor._
import akka.util.Helpers.base64
import scala.util.{Failure, Success, Try}

/**
 * @author steven
 *
 */
object Manager {
    def props = {
        Props(new Manager)
    }

    private case class ConnectionKey(brokerUrl: String, userAndPass: Option[(String, String)])
}

class Manager extends Actor {
    private var connectionFactories = Map.empty[ConnectionKey, ActorRef]

    private val connFactName = Iterator from 0 map (i ⇒ s"Connection${base64(i)}")

    private def getConnectionFact(key: ConnectionKey, staticName: Option[String]): Try[ActorRef] = Try {
        connectionFactories.getOrElse(key, {
            val connFact = {
                key.userAndPass.fold(new ActiveMQConnectionFactory(key.brokerUrl))(
                    uandp ⇒ new ActiveMQConnectionFactory(uandp._1, uandp._2, key.brokerUrl))
            }

            val name = staticName.fold(connFactName.next())(identity)
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
        case req@GetConnection(brokerUrl, _, _) ⇒
            openConnection(ConnectionKey(brokerUrl, None), req)

        case req@GetAuthenticatedConnection(brokerUrl, user, pass, _, _) ⇒
            openConnection(ConnectionKey(brokerUrl, Some(user → pass)), req)

        case Terminated(act) ⇒ connectionFactories find (_._2 == act) foreach (kv ⇒ connectionFactories -= kv._1)
    }
}
