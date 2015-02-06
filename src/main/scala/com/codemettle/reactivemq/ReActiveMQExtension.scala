/*
 * ReActiveMQExtension.scala
 *
 * Updated: Feb 6, 2015
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

/**
 * @author steven
 *
 */
object ReActiveMQExtensionImpl {
    private[reactivemq] class ConnectionFactoryHolder {
        private var connFacts = Map.empty[ConnectionKey, ConnectionFactory]

        def getConnectionFactory(forKey: ConnectionKey): ConnectionFactory = synchronized {
            connFacts.getOrElse(forKey, {
                val ret = ConnectionFactory(forKey)
                connFacts += (forKey → ret)
                ret
            })
        }

        def closeConnectionFactory(forKey: ConnectionKey): Unit = synchronized {
            connFacts get forKey foreach (_.cleanup())
            connFacts -= forKey
        }

        def cleanup() = synchronized {
            connFacts.values foreach (_.cleanup())
            connFacts = Map.empty
        }
    }
}

class ReActiveMQExtensionImpl(config: ReActiveMQConfig)(implicit system: ActorSystem) extends Extension {
    private val connFactHolder = new ConnectionFactoryHolder

    system registerOnTermination connFactHolder.cleanup()

    val manager = system.actorOf(Manager props connFactHolder, "reActiveMQ")

    val autoConnects: Map[String, ActorRef] = {
        import system.dispatcher
        import akka.pattern.ask
        import spray.util.pimpFuture
        implicit val timeout = Timeout(10.seconds)

        val futures = config.autoConnections map (e ⇒ (manager ? AutoConnect(e._2, e._1)).mapTo[ConnectionEstablished])

        val mapF = Future sequence futures map (conns ⇒ (conns map (ce ⇒ ce.request.staticActorName.get → ce.connectionActor)).toMap)

        mapF.await
    }
}

object ReActiveMQExtension extends ExtensionId[ReActiveMQExtensionImpl] with ExtensionIdProvider {
    override def createExtension(system: ExtendedActorSystem) = {
        new ReActiveMQExtensionImpl(ReActiveMQConfig(system))(system)
    }

    override def lookup() = ReActiveMQExtension
}
