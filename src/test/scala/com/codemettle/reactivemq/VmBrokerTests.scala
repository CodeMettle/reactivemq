/*
 * VmBrokerTests.scala
 *
 * Updated: Feb 3, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

import java.{lang => jl}

import ch.qos.logback.classic.{Level, LoggerContext}
import com.typesafe.config.ConfigFactory
import org.apache.activemq.broker.BrokerService
import org.apache.activemq.camel.component.ActiveMQComponent.activeMQComponent
import org.apache.activemq.store.memory.MemoryPersistenceAdapter
import org.scalatest._
import org.slf4j.{Logger, LoggerFactory}

import com.codemettle.reactivemq.ReActiveMQMessages._
import com.codemettle.reactivemq.VmBrokerTests.{logLevel, testConfig}
import com.codemettle.reactivemq.model.{AMQMessage, JMSMessageProperties, Queue}

import akka.actor._
import akka.camel.{CamelExtension, CamelMessage, Consumer}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success, Try}

/**
 * @author steven
 *
 */
object VmBrokerTests {
    val nonErrorLogging = false

    val reconnConfig = "reactivemq.reestablish-attempt-delay = 1 second"

    def logConfig = s"akka.loglevel = ${if (nonErrorLogging) "INFO" else "ERROR"}"

    def logLevel = if (nonErrorLogging) Level.INFO else Level.ERROR

    def testConfig = List(reconnConfig, logConfig) mkString "\n"
}

class VmBrokerTests(_system: ActorSystem) extends TestKit(_system) with FlatSpecLike with Matchers with BeforeAndAfterAll with OptionValues {
    def this() = this(ActorSystem("ReActiveMQTest", ConfigFactory parseString testConfig withFallback ConfigFactory.load()))

    private var broker = Option.empty[BrokerService]
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

    private def startBroker() = {
        val broker = new BrokerService
        broker.setBrokerName(brokerName)
        broker.setUseJmx(false)
        broker.setPersistent(false)
        broker.setPersistenceAdapter(new MemoryPersistenceAdapter)
        broker.getSystemUsage.getMemoryUsage.setLimit(1024L * 1024 * 300) // 300 MB
        broker.getSystemUsage.getTempUsage.setLimit(1024L * 1024 * 50) // 50 MB
        broker.start()

        this.broker = Some(broker)
    }

    private def stopBroker() = {
        broker foreach (_.stop())
        broker = None
    }

    override protected def beforeAll(): Unit = {
        setLogging()

        startBroker()

        CamelExtension(system).context.addComponent("embedded", activeMQComponent(s"vm://$brokerName"))
    }

    override protected def afterAll(): Unit = {
        Thread.sleep(250)

        stopBroker()

        Thread.sleep(250)

        shutdown(duration = 35.seconds, verifySystemShutdown = true)
    }

    private def getConnectionEstablished = {
        val probe = TestProbe()

        probe.send(manager, GetConnection(s"vm://$brokerName", Some(brokerName)))

        val connEst = probe.expectMsgType[ConnectionEstablished]

        system stop probe.ref

        connEst
    }

    private def getConnection = getConnectionEstablished.connectionActor

    private def closeConnection(conn: ActorRef) = {
        conn ! CloseConnection
    }

    "ReActiveMQ" should "connect" in {
        val conn = getConnection
        closeConnection(conn)
    }

    it should "have stable connection names" in {
        val connEst = getConnectionEstablished

        val sel = system.actorSelection(s"/user/reActiveMQ/$brokerName")

        val probe = TestProbe()

        sel.tell(Identify("c"), probe.ref)

        val ident = probe.expectMsgType[ActorIdentity]

        ident.correlationId should equal ("c")
        ident.ref.value should equal (connEst.connectionActor)

        closeConnection(ident.ref.value)
    }

    it should "successfully monitor connection state" in {
        val testProbe = TestProbe()

        val conn = getConnection

        testProbe.send(conn, SubscribeToConnectionStatus)

        testProbe.expectMsg(ConnectionReestablished(conn))

        stopBroker()

        testProbe.expectMsg(ConnectionInterrupted(conn))

        startBroker()

        testProbe.expectMsg(ConnectionReestablished(conn))

        closeConnection(conn)

        system stop testProbe.ref
    }

    it should "send messages" in {
        val conn = getConnection

        val receiver = TestProbe()
        val cons = TestActorRef(new Consumer {
            override def endpointUri: String = "embedded:recvtest"

            override def receive: Actor.Receive = {
                case msg ⇒ receiver.ref ! msg
            }
        })

        val camelExt = CamelExtension(system)
        implicit val camelCtx = camelExt.context
        camelExt.template.sendBody("embedded:recvtest", "hi")

        val testmsg = receiver.expectMsgType[CamelMessage]

        testmsg.body should equal ("hi")

        val probe = TestProbe()

        probe.send(conn, SendMessage(Queue("recvtest"), AMQMessage("hello")))
        probe.expectMsgType[Unit]

        val msg = receiver.expectMsgType[CamelMessage]

        msg.body should equal ("hello")

        probe.send(conn, SendMessage(Queue("recvtest"),
            AMQMessage("headertest", JMSMessageProperties().copy(`type` = Some("test"), correlationID = Some("x")),
                Map("testheader" → true, "head2" → 3))))
        probe.expectMsgType[Unit]

        val headertest = receiver.expectMsgType[CamelMessage]

        headertest.body should equal ("headertest")
        headertest.headerAs[String]("JMSType").get should equal ("test")
        headertest.headerAs[String]("JMSCorrelationID").get should equal ("x")
        headertest.headerAs[Boolean]("testheader").get should equal (true)
        headertest.headerAs[Int]("head2").get should equal (3)

        probe watch cons
        cons ! PoisonPill

        probe.expectMsgType[Terminated]

        closeConnection(conn)
    }

    it should "support request/reply" in {
        val conn = getConnection

        val cons = TestActorRef(new Consumer {
            override def endpointUri: String = "embedded:testrr"

            override def receive: Actor.Receive = {
                case CamelMessage(msg: String, _) ⇒ sender() ! msg.reverse
                case CamelMessage(msg: jl.Integer, _) ⇒ sender() ! Int.box(0 - msg.intValue())
                case msg: CamelMessage ⇒ sender() ! CamelMessage(msg.headerAs[AnyRef]("replyWith").get, Map("original" → msg.body))
            }
        })

        val probe = TestProbe()

        probe.send(conn, RequestMessage(Queue("testrr"), AMQMessage("hello")))

        probe.expectMsgType[AMQMessage].body should equal ("olleh")

        probe.send(conn, RequestMessage(Queue("testrr"), AMQMessage(3)))

        probe.expectMsgType[AMQMessage].body should equal (-3)

        probe.send(conn, RequestMessage(Queue("testrr"), AMQMessage(true, headers = Map("replyWith" → "reply"))))

        val msg = probe.expectMsgType[AMQMessage]
        msg.body should equal ("reply")
        msg.headers should contain key "original"
        msg.headers("original") should equal (true)

        probe watch cons
        cons ! PoisonPill

        probe.expectMsgType[Terminated]

        closeConnection(conn)
    }
}
