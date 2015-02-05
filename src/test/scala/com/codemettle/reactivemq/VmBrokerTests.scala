/*
 * VmBrokerTests.scala
 *
 * Updated: Feb 4, 2015
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

import com.codemettle.reactivemq.Producer.Oneway
import com.codemettle.reactivemq.ReActiveMQMessages._
import com.codemettle.reactivemq.VmBrokerTests.{nonErrorLogging, logLevel, testConfig}
import com.codemettle.reactivemq.model._

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
            val lc = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
            lc.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(logLevel)
            if (!nonErrorLogging)
                lc.getLogger("com.codemettle.reactivemq.connection.ConnectionActor").setLevel(Level.OFF)
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

        CamelExtension(system).context.addComponent("embedded", activeMQComponent(s"vm://$brokerName?create=false"))
    }

    override protected def afterAll(): Unit = {
        Thread.sleep(250)

        stopBroker()

        Thread.sleep(250)

        shutdown(duration = 35.seconds, verifySystemShutdown = true)
    }

    private def getConnectionEstablished = {
        val probe = TestProbe()

        probe.send(manager, GetConnection(s"vm://$brokerName?create=false", Some(brokerName)))

        val connEst = probe.expectMsgType[ConnectionEstablished]

        system stop probe.ref

        connEst
    }

    private def getConnection = getConnectionEstablished.connectionActor

    private def closeConnection(conn: ActorRef) = {
        conn ! CloseConnection
    }

    class ConnectionListener(conn: ActorRef) {
        val probe = TestProbe()

        probe.send(conn, SubscribeToConnectionStatus)

        def waitForConnection() = probe.expectMsgType[ConnectionReestablished]

        def waitForDisconnect() = probe.expectMsgType[ConnectionInterrupted]

        def done() = system stop probe.ref
    }

    "ReActiveMQ" should "connect" in {
        val conn = getConnection
        closeConnection(conn)
    }

    it should "error on invalid URL" in {
        val probe = TestProbe()

        probe.send(manager, GetConnection(s"vm://blah?create=false"))

        probe.expectMsgType[ConnectionFailed]
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

    it should "support consuming from topics" in {
        val conn = getConnection

        val probe1 = TestProbe()
        val probe2 = TestProbe()

        probe1.send(conn, ConsumeFromTopic("sharedtopic"))
        probe2.send(conn, Consume(Topic("sharedtopic"), sharedConsumer = true))

        Thread.sleep(250)

        CamelExtension(system).template.sendBody("embedded:topic:sharedtopic", "topic test")

        probe1.expectMsgType[AMQMessage].body should equal ("topic test")
        probe2.expectMsgType[AMQMessage].body should equal ("topic test")

        system stop probe1.ref
        system stop probe2.ref

        closeConnection(conn)
    }

    it should "support consuming from topics (dedicated)" in {
        val conn = getConnection

        val probe1 = TestProbe()
        val probe2 = TestProbe()

        probe1.send(conn, ConsumeFromTopic("sharedtopic2", sharedConsumer = false))
        probe2.send(conn, Consume(Topic("sharedtopic2"), sharedConsumer = false))

        probe1.expectMsg(ConsumeSuccess(Topic("sharedtopic2")))
        probe2.expectMsg(ConsumeSuccess(Topic("sharedtopic2")))

        CamelExtension(system).template.sendBody("embedded:topic:sharedtopic2", "topic test")

        probe1.expectMsgType[AMQMessage].body should equal ("topic test")
        probe2.expectMsgType[AMQMessage].body should equal ("topic test")

        system stop probe1.ref
        system stop probe2.ref

        closeConnection(conn)
    }

    it should "support consuming from queues" in {
        val conn = getConnection

        val probe1 = TestProbe()
        val probe2 = TestProbe()

        probe1.send(conn, ConsumeFromQueue("q1"))
        probe2.send(conn, Consume(Queue("q2"), sharedConsumer = false))

        probe1.expectMsg(ConsumeSuccess(Queue("q1")))
        probe2.expectMsg(ConsumeSuccess(Queue("q2")))

        CamelExtension(system).template.sendBody("embedded:q1", "queue test")
        CamelExtension(system).template.sendBody("embedded:q2", "queue test")

        probe1.expectMsgType[AMQMessage].body should equal ("queue test")
        probe2.expectMsgType[AMQMessage].body should equal ("queue test")

        system stop probe1.ref
        system stop probe2.ref

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

    it should "properly set ttl" in {
        val conn = getConnection

        val receiver = TestProbe()
        receiver.send(conn, ConsumeFromQueue("ttl"))
        receiver.expectMsgType[ConsumeSuccess]

        val sender = TestProbe()
        val now = System.currentTimeMillis()
        sender.send(conn, SendMessage(Queue("ttl"), AMQMessage("hi"), timeToLive = 500))

        val msg = receiver.expectMsgType[AMQMessage]

        msg.properties.expiration should be ((now + 500) +- 10)

        system stop receiver.ref

        closeConnection(conn)
    }

    it should "properly set ttl in request/reply" in {
        val conn = getConnection

        val receiver = TestProbe()
        receiver.send(conn, ConsumeFromQueue("ttl2"))
        receiver.expectMsgType[ConsumeSuccess]

        val sender = TestProbe()
        val now = System.currentTimeMillis()
        sender.send(conn, RequestMessage(Queue("ttl2"), AMQMessage("hi"), 500.millis))
        sender.expectMsgType[Status.Failure]

        val msg = receiver.expectMsgType[AMQMessage]

        msg.properties.expiration should be ((now + 500) +- 10)

        system stop receiver.ref

        closeConnection(conn)
    }

    class TestQueueConsumer(qName: String, val connection: ActorRef) extends QueueConsumer {
        def consumeFrom = Queue(qName)

        def receive = {
            case AMQMessage(msg: String, _, _) ⇒ sender() ! msg.reverse
            case AMQMessage(msg: jl.Integer, _, _) ⇒ sender() ! Int.box(0 - msg.intValue())
            case msg: AMQMessage ⇒ sender() ! AMQMessage(msg.headers("replyWith"), headers = Map("original" → msg.body))
        }
    }

    it should "correctly respond to QueueConsumer messages" in {
        val conn = getConnection

        val cons = TestActorRef(new TestQueueConsumer("testQueueCons", conn))

        val probe = TestProbe()

        probe.send(conn, RequestMessage(Queue("testQueueCons"), AMQMessage("hello")))

        probe.expectMsgType[AMQMessage].body should equal ("olleh")

        probe.send(conn, RequestMessage(Queue("testQueueCons"), AMQMessage(3)))

        probe.expectMsgType[AMQMessage].body should equal (-3)

        probe.send(conn, RequestMessage(Queue("testQueueCons"), AMQMessage(true, headers = Map("replyWith" → "reply"))))

        val msg = probe.expectMsgType[AMQMessage]
        msg.body should equal ("reply")
        msg.headers should contain key "original"
        msg.headers("original") should equal (true)

        probe watch cons
        cons ! PoisonPill

        probe.expectMsgType[Terminated]

        closeConnection(conn)
    }

    class TestTopicConsumer(tName: String, val connection: ActorRef, probe: TestProbe) extends TopicConsumer {
        def consumeFrom = Topic(tName)

        def receive = {
            case msg ⇒ probe.ref forward msg
        }
    }

    it should "allow TopicConsumers to receive messages" in {
        val conn = getConnection

        val probe1 = TestProbe()
        val probe2 = TestProbe()

        val cons1 = TestActorRef(new TestTopicConsumer("testTopicCons", conn, probe1))
        val cons2 = TestActorRef(new TestTopicConsumer("testTopicCons", conn, probe2))

        Thread.sleep(250)

        CamelExtension(system).template.sendBody("embedded:topic:testTopicCons", "topic test")

        probe1.expectMsgType[AMQMessage].body should equal ("topic test")
        probe2.expectMsgType[AMQMessage].body should equal ("topic test")

        system stop probe1.ref
        system stop probe2.ref
        system stop cons1
        system stop cons2

        closeConnection(conn)
    }

    class TestOnewayProducer(val destination: Destination, val connection: ActorRef) extends Producer with Oneway

    it should "allow Oneway Producers to send messages" in {
        val conn = getConnection

        val consumer = TestProbe()

        consumer.send(conn, ConsumeFromQueue("oneway"))
        consumer.expectMsgType[ConsumeSuccess]

        val prod = TestActorRef(new TestOnewayProducer(Queue("oneway"), conn))

        val probe2 = TestProbe()

        probe2.send(prod, "test")
        consumer.expectMsgType[AMQMessage].body should equal ("test")
        probe2.expectNoMsg(500.millis)

        system stop prod
        system stop consumer.ref

        closeConnection(conn)
    }

    class TestOnewayStatusProducer(val destination: Destination, val connection: ActorRef) extends Producer with Oneway {
        override protected def swallowSendStatus: Boolean = false
    }

    it should "allow Oneway Producers to send messages and return status" in {
        val conn = getConnection

        val consumer = TestProbe()

        consumer.send(conn, ConsumeFromQueue("oneway"))
        consumer.expectMsgType[ConsumeSuccess]

        val prod = TestActorRef(new TestOnewayStatusProducer(Queue("oneway"), conn))

        val probe2 = TestProbe()

        probe2.send(prod, "status test")
        consumer.expectMsgType[AMQMessage].body should equal ("status test")
        probe2.expectMsgType[Unit]

        system stop prod
        system stop consumer.ref

        closeConnection(conn)
    }

    class TestProducer(val destination: Destination, val connection: ActorRef) extends Producer

    it should "allow request/reply Producers to send/receive messages" in {
        val conn = getConnection

        val qcons = TestActorRef(new TestQueueConsumer("prodrrtest", conn))

        val prod = TestActorRef(new TestProducer(Queue("prodrrtest"), conn))

        val probe = TestProbe()

        probe.send(prod, "hello")

        probe.expectMsgType[AMQMessage].body should equal ("olleh")

        probe.send(prod, 3)

        probe.expectMsgType[AMQMessage].body should equal (-3)

        probe.send(prod, AMQMessage(true, headers = Map("replyWith" → "reply")))

        val msg = probe.expectMsgType[AMQMessage]
        msg.body should equal ("reply")
        msg.headers should contain key "original"
        msg.headers("original") should equal (true)

        system stop prod

        system stop qcons

        closeConnection(conn)
    }

    class TestTransformProducer(val destination: Destination, val connection: ActorRef) extends Producer {
        override protected def transformOutgoingMessage(msg: Any): Any = msg match {
            case str: String ⇒ str.reverse
            case num: jl.Integer ⇒ 0 - num.intValue()
            case AMQMessage(_, _, headers) ⇒ AMQMessage(headers("newBody"))
            case _ ⇒ msg
        }
    }

    it should "allow producers to transform outgoing messages" in {
        val conn = getConnection

        val qcons = TestActorRef(new TestQueueConsumer("prodrrtest", conn))

        val prod = TestActorRef(new TestTransformProducer(Queue("prodrrtest"), conn))

        val probe = TestProbe()

        probe.send(prod, "hello")

        probe.expectMsgType[AMQMessage].body should equal ("hello")

        probe.send(prod, 3)

        probe.expectMsgType[AMQMessage].body should equal (3)

        probe.send(prod, AMQMessage(true, headers = Map("newBody" → "reply")))

        probe.expectMsgType[AMQMessage].body should equal ("ylper")

        system stop prod

        system stop qcons

        closeConnection(conn)
    }

    it should "time out sends while disconnected" in {
        val conn = getConnection

        val probe = TestProbe()

        val listener = new ConnectionListener(conn)
        listener.waitForConnection()

        stopBroker()

        listener.waitForDisconnect()

        probe.send(conn, SendMessage(Queue("x"), AMQMessage("hi"), timeout = 150.millis))

        probe.expectMsgType[Status.Failure].cause.getMessage should equal ("Connection timed out")

        startBroker()

        listener.done()

        closeConnection(conn)
    }

    it should "time out request/reply while disconnected" in {
        val conn = getConnection

        val probe = TestProbe()

        val listener = new ConnectionListener(conn)
        listener.waitForConnection()

        stopBroker()

        listener.waitForDisconnect()

        probe.send(conn, RequestMessage(Queue("x"), AMQMessage("hi"), timeout = 150.millis))

        probe.expectMsgType[Status.Failure].cause.getMessage should equal ("Connection timed out")

        startBroker()

        Thread.sleep(50)

        getConnection

        listener.done()

        closeConnection(conn)
    }
}
