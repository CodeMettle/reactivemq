/*
 * VmBrokerTests.scala
 *
 * Updated: Feb 3, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

import ch.qos.logback.classic.{Level, LoggerContext}
import com.typesafe.config.ConfigFactory
import org.apache.activemq.broker.BrokerService
import org.apache.activemq.store.memory.MemoryPersistenceAdapter
import org.scalatest._
import org.slf4j.{Logger, LoggerFactory}

import com.codemettle.reactivemq.ReActiveMQMessages.{CloseConnection, ConnectionEstablished, GetConnection}
import com.codemettle.reactivemq.VmBrokerTests.{logLevel, logConfig}

import akka.actor.{ActorIdentity, ActorSystem, Identify}
import akka.testkit.{TestKit, TestProbe}
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success, Try}

/**
 * @author steven
 *
 */
object VmBrokerTests {
    val nonErrorLogging = false

    def logConfig = s"akka.loglevel = ${if (nonErrorLogging) "INFO" else "ERROR"}"

    def logLevel = if (nonErrorLogging) Level.INFO else Level.ERROR
}

class VmBrokerTests(_system: ActorSystem) extends TestKit(_system) with FlatSpecLike with Matchers with BeforeAndAfterAll with OptionValues {
    def this() = this(ActorSystem("ReActiveMQTest", ConfigFactory parseString logConfig withFallback ConfigFactory.load()))

    private var broker: BrokerService = _
    private val brokerName = (Random.alphanumeric take 5) mkString ""

    private def manager = ReActiveMQExtension(system).manager

    private def setLogging(): Unit = {
        Try {
            LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext].getLogger(Logger.ROOT_LOGGER_NAME).setLevel(logLevel)
        } match {
            case Success(_) ⇒
            case Failure(_) ⇒
                Thread.sleep(500)
                setLogging()
        }
    }

    override protected def beforeAll(): Unit = {
        setLogging()

        broker = new BrokerService
        broker.setBrokerName(brokerName)
        broker.setUseJmx(false)
        broker.setPersistent(false)
        broker.setPersistenceAdapter(new MemoryPersistenceAdapter)
        broker.getSystemUsage.getMemoryUsage.setLimit(1024L * 1024 * 300) // 300 MB
        broker.getSystemUsage.getTempUsage.setLimit(1024L * 1024 * 50) // 50 MB
        broker.start()
    }

    override protected def afterAll(): Unit = {
        broker.stop()

        shutdown(duration = 35.seconds, verifySystemShutdown = true)
    }

    "ReActiveMQ" should "connect" in {
        val testProbe = TestProbe()

        testProbe.send(manager, GetConnection(s"vm://$brokerName", Some(brokerName)))

        val connEst = testProbe.expectMsgType[ConnectionEstablished]

        testProbe.send(connEst.connectionActor, CloseConnection)
    }

    it should "have stable connection names" in {
        val testProbe = TestProbe()

        testProbe.send(manager, GetConnection(s"vm://$brokerName", Some(brokerName)))

        val connEst = testProbe.expectMsgType[ConnectionEstablished]

        val sel = system.actorSelection(s"/user/reActiveMQ/$brokerName")

        sel.tell(Identify("c"), testProbe.ref)

        val ident = testProbe.expectMsgType[ActorIdentity]

        ident.correlationId should equal ("c")
        ident.ref.value should equal (connEst.connectionActor)

        testProbe.send(ident.ref.value, CloseConnection)
    }
}
