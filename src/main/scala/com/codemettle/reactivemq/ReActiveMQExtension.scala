/*
 * ReActiveMQExtension.scala
 *
 * Updated: Feb 19, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

import com.codemettle.reactivemq.ReActiveMQExtensionImpl.ConnectionFactoryHolder
import com.codemettle.reactivemq.ReActiveMQMessages.{ConnectionEstablished, AutoConnect}
import com.codemettle.reactivemq.activemq.ConnectionFactory
import com.codemettle.reactivemq.activemq.ConnectionFactory.ConnectionKey
import com.codemettle.reactivemq.config.ReActiveMQConfig

import akka.actor._
import akka.util.Timeout
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Failure, Try}

/**
 * @author steven
 *
 */
object ReActiveMQExtensionImpl {
    private[reactivemq] class ConnectionFactoryHolder(config: ReActiveMQConfig) {
        private var connFacts = Map.empty[ConnectionKey, ConnectionFactory]

        def getConnectionFactory(forKey: ConnectionKey): ConnectionFactory = synchronized {
            connFacts.getOrElse(forKey, {
                val ret = ConnectionFactory(forKey, config)
                connFacts += (forKey → ret)
                ret
            })
        }

        def closeConnectionFactory(forKey: ConnectionKey): Unit = synchronized {
            connFacts get forKey foreach (_.cleanup())
            connFacts -= forKey
        }

        def cleanup(): Unit = synchronized {
            connFacts.values foreach (_.cleanup())
            connFacts = Map.empty
        }
    }
}

class ReActiveMQExtensionImpl(config: ReActiveMQConfig)(implicit system: ExtendedActorSystem) extends Extension {
    private val connFactHolder = new ConnectionFactoryHolder(ReActiveMQConfig(system))

    system registerOnTermination connFactHolder.cleanup()

    final val manager = system.systemActorOf(Manager props connFactHolder, "reActiveMQ")

    val autoConnects: Map[String, ActorRef] = Try {
        import system.dispatcher
        import akka.pattern.ask
        import com.codemettle.reactivemq.util._
        implicit val timeout: Timeout = Timeout(config.autoconnectTimeout + 2.seconds)

        val futures = config.autoConnections map (e ⇒ (manager ? AutoConnect(e._2, e._1, config.autoconnectTimeout)).mapTo[ConnectionEstablished])

        val mapF = Future sequence futures map (conns ⇒ (conns map (ce ⇒ ce.request.staticActorName.get → ce.connectionActor)).toMap)

        mapF.await(config.autoconnectTimeout + 5.seconds)
    } match {
        case Success(ac) ⇒ ac
        case Failure(t) ⇒
            system stop manager
            throw t
    }
}

object ReActiveMQExtension extends ExtensionId[ReActiveMQExtensionImpl] with ExtensionIdProvider {
    override def createExtension(system: ExtendedActorSystem): ReActiveMQExtensionImpl = {
        new ReActiveMQExtensionImpl(ReActiveMQConfig(system))(system)
    }

    override def lookup(): ExtensionId[ReActiveMQExtensionImpl] = ReActiveMQExtension
}
