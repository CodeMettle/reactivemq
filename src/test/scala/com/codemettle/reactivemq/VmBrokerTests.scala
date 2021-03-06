/*
 * VmBrokerTests.scala
 *
 * Updated: Feb 19, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

import java.{lang => jl, util => ju}

import ch.qos.logback.classic.{Level, LoggerContext}
import com.typesafe.config.ConfigFactory
import org.apache.activemq.broker.BrokerService
import org.apache.activemq.camel.component.ActiveMQComponent.activeMQComponent
import org.apache.activemq.security.{AuthenticationUser, SimpleAuthenticationPlugin}
import org.apache.activemq.store.memory.MemoryPersistenceAdapter
import org.apache.camel
import org.scalatest._
import org.slf4j.{Logger, LoggerFactory}

import com.codemettle.reactivemq.CollectionConverters._
import com.codemettle.reactivemq.ReActiveMQMessages._
import com.codemettle.reactivemq.VmBrokerTests.{logLevel, nonErrorLogging, testConfig}
import com.codemettle.reactivemq.model._

import akka.actor._
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}
import scala.util.{Failure, Random, Success, Try}

/**
 * @author steven
 *
 */
object VmBrokerTests {
    val nonErrorLogging = false

    val reconnConfig = "reactivemq.reestablish-attempt-delay = 1 second"

    def logConfig = s"akka.loglevel = ${if (nonErrorLogging) "INFO" else "ERROR"}"

    def logLevel: Level = if (nonErrorLogging) Level.INFO else Level.ERROR

    def testConfig: String = List(reconnConfig, logConfig) mkString "\n"
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
            case Success(_) =>
            case Failure(_) =>
                Thread.sleep(500)
                setLogging()
        }
    }

    private def startBroker(username: Option[String] = None, password: Option[String] = None): Unit = {
        val broker = new BrokerService
        for (u <- username; p <- password) {
            def authPlug = {
                def user = new AuthenticationUser(u, p, "users")
                val plug = new SimpleAuthenticationPlugin()
                plug.setAnonymousAccessAllowed(false)
                plug.setUsers(ju.Arrays.asList(user))
                plug
            }

            broker.setPlugins(Array(authPlug))
        }
        broker.setBrokerName(brokerName)
        broker.setUseJmx(false)
        broker.setPersistent(false)
        broker.setPersistenceAdapter(new MemoryPersistenceAdapter)
        broker.getSystemUsage.getMemoryUsage.setLimit(1024L * 1024 * 300) // 300 MB
        broker.getSystemUsage.getTempUsage.setLimit(1024L * 1024 * 50) // 50 MB
        broker.start()

        this.broker = Some(broker)
    }

    private def stopBroker(): Unit = {
        broker foreach (_.stop())
        broker = None
    }

    private def restartBroker(username: Option[String] = None, password: Option[String] = None): Unit = {
        stopBroker()
        startBroker(username, password)
    }

    private val camelContext = new camel.impl.DefaultCamelContext()
    camelContext.start()
    private val producer = camelContext.createProducerTemplate()

    override protected def beforeAll(): Unit = {
        setLogging()

        // Needed post-5.15.3 for some reason to serialize Integer.
        System.setProperty("org.apache.activemq.SERIALIZABLE_PACKAGES", "java.lang")

        startBroker()

        val p = Promise[Unit]()

        val lss = new camel.support.LifecycleStrategySupport {
            override def onComponentAdd(name: String, component: camel.Component): Unit = {
                if (name == "embedded")
                    p.trySuccess({})
            }
        }
        camelContext.addLifecycleStrategy(lss)

        camelContext.addComponent("embedded", activeMQComponent(s"vm://$brokerName?create=false"))

        Await.ready(p.future, Duration.Inf)

        camelContext.setLifecycleStrategies(camelContext.getLifecycleStrategies.asScala.filterNot(_ == lss).asJava)
    }

    override protected def afterAll(): Unit = {
        camelContext.stop()

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

    private def closeConnection(conn: ActorRef): Unit = {
        conn ! CloseConnection
    }

    class ConnectionListener(conn: ActorRef) {
        val probe = TestProbe()

        probe.send(conn, SubscribeToConnectionStatus)

        def waitForConnection(): ConnectionReestablished = probe.expectMsgType[ConnectionReestablished]

        def waitForDisconnect(): ConnectionInterrupted = probe.expectMsgType[ConnectionInterrupted]

        def done(): Unit = system stop probe.ref
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

    it should "return auth error" in {
        restartBroker(Some("user"), Some("pass"))

        val probe = TestProbe()

        probe.send(manager, GetConnection(s"vm://$brokerName?create=false", Some(brokerName)))

        probe.expectMsgType[ConnectionFailed].cause shouldBe a[javax.jms.JMSSecurityException]

        system stop probe.ref

        restartBroker()
    }

    it should "connect with authentication" in {
        restartBroker(Some("user"), Some("pass"))

        val probe = TestProbe()

        probe.send(manager, GetAuthenticatedConnection(s"vm://$brokerName?create=false", "user", "pass"))

        val connEst = probe.expectMsgType[ConnectionEstablished]

        closeConnection(connEst.connectionActor)

        system stop probe.ref

        restartBroker()
    }

    it should "have stable connection names" in {
        val connEst = getConnectionEstablished

        val sel = system.actorSelection(s"/system/reActiveMQ/$brokerName")

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

        testProbe.expectMsg(ConnectionReestablished(conn, initialStateNotification = true))

        stopBroker()

        testProbe.expectMsg(ConnectionInterrupted(conn, initialStateNotification = false))

        startBroker()

        testProbe.expectMsg(ConnectionReestablished(conn, initialStateNotification = false))

        closeConnection(conn)

        system stop testProbe.ref
    }

    it should "send messages" in {
        val conn = getConnection

        val receiver = TestProbe()

        val rb = new camel.builder.RouteBuilder() {
            override def configure(): Unit = {
                from("embedded:recvtest")
                  .setExchangePattern(camel.ExchangePattern.InOnly)
                  .bean(new AnyRef {
                      def onMessage(m: camel.Message): Unit = {
                        receiver.ref ! m
                      }
                  })
            }
        }

        val p = Promise[Unit]()

        val lss = new camel.support.LifecycleStrategySupport {
            override def onRoutesAdd(routes: ju.Collection[camel.Route]): Unit = {
                p.trySuccess({})
            }
        }
        camelContext.addLifecycleStrategy(lss)
        camelContext.addRoutes(rb)

        Await.ready(p.future, Duration.Inf)

        producer.sendBody("embedded:recvtest", "hi")

        val testmsg = receiver.expectMsgType[camel.Message]

        testmsg.getBody should equal ("hi")

        val probe = TestProbe()

        probe.send(conn, SendMessage(Queue("recvtest"), AMQMessage("hello")))
        probe.expectMsg(SendAck)

        val msg = receiver.expectMsgType[camel.Message]

        msg.getBody should equal ("hello")

        probe.send(conn, SendMessage(Queue("recvtest"),
            AMQMessage("headertest", JMSMessageProperties().copy(`type` = Some("test"), correlationID = Some("x")),
                Map("testheader" -> true, "head2" -> 3))))
        probe.expectMsg(SendAck)

        val headertest = receiver.expectMsgType[camel.Message]

        headertest.getBody should equal ("headertest")
        headertest.getHeader("JMSType") should equal ("test")
        headertest.getHeader("JMSCorrelationID") should equal ("x")
        headertest.getHeader("testheader") should equal (true)
        headertest.getHeader("head2") should equal (3)

        camelContext.removeRouteDefinitions(camelContext.getRouteDefinitions.asScala.toList.asJava)
        camelContext.setLifecycleStrategies(camelContext.getLifecycleStrategies.asScala.filterNot(_ == lss).asJava)

        closeConnection(conn)
    }

    it should "support consuming from topics" in {
        val conn = getConnection

        val probe1 = TestProbe()
        val probe2 = TestProbe()

        probe1.send(conn, ConsumeFromTopic("sharedtopic"))
        probe2.send(conn, Consume(Topic("sharedtopic"), sharedConsumer = true))

        Thread.sleep(250)

        producer.sendBody("embedded:topic:sharedtopic", "topic test")

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

        producer.sendBody("embedded:topic:sharedtopic2", "topic test")

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

        producer.sendBody("embedded:q1", "queue test")
        producer.sendBody("embedded:q2", "queue test")

        probe1.expectMsgType[AMQMessage].body should equal ("queue test")
        probe2.expectMsgType[AMQMessage].body should equal ("queue test")

        system stop probe1.ref
        system stop probe2.ref

        closeConnection(conn)
    }

    it should "support request/reply" in {
        val conn = getConnection

        val rb = new camel.builder.RouteBuilder() {
            override def configure(): Unit = {
                from("embedded:testrr")
                    .process(new camel.Processor {
                        override def process(exchange: camel.Exchange): Unit = {
                            exchange.getIn.getBody match {
                                case msg: String => exchange.getIn.setBody(msg.reverse)
                                case msg: jl.Integer => exchange.getIn.setBody(Int.box(0 - msg.intValue()))
                                case _ =>
                                    val in = exchange.getIn
                                    val origBody = in.getBody
                                    in.setBody(in.getHeader("replyWith"))
                                    in.setHeader("original", origBody)
                            }
                        }
                    })
            }
        }

        val p = Promise[Unit]()
        val lss = new camel.support.LifecycleStrategySupport {
            override def onRoutesAdd(routes: ju.Collection[camel.Route]): Unit = {
                p.trySuccess({})
            }
        }
        camelContext.addLifecycleStrategy(lss)
        camelContext.addRoutes(rb)

        Await.ready(p.future, Duration.Inf)

        val probe = TestProbe()

        probe.send(conn, RequestMessage(Queue("testrr"), AMQMessage("hello")))

        probe.expectMsgType[AMQMessage].body should equal ("olleh")

        probe.send(conn, RequestMessage(Queue("testrr"), AMQMessage(3)))

        probe.expectMsgType[AMQMessage].body should equal (-3)

        probe.send(conn, RequestMessage(Queue("testrr"), AMQMessage(true, headers = Map("replyWith" -> "reply"))))

        val msg = probe.expectMsgType[AMQMessage]
        msg.body should equal ("reply")
        msg.headers should contain key "original"
        msg.headers("original") should equal (true)

        camelContext.removeRouteDefinitions(camelContext.getRouteDefinitions.asScala.toList.asJava)
        camelContext.setLifecycleStrategies(camelContext.getLifecycleStrategies.asScala.filterNot(_ == lss).asJava)

        closeConnection(conn)
    }

    // http://docs.oracle.com/cd/E13171_01/alsb/docs25/interopjms/MsgIDPatternforJMS.html
    //  "In the request message, the ID can be stored as a correlation ID property or simply a message ID property.
    //   When used as a correlation ID, this can cause confusion about which message is the request and which is the
    //   reply. If a request has a message ID but no correlation ID, then a reply has a correlation ID that is the same
    //   as the request's message ID."

    it should "fallback to messageID in the case of no correlationID" in {
        val conn = getConnection

        val probe = TestProbe()
        val probe2 = TestProbe()

        val cons1 = TestActorRef(new QueueConsumer {
            override def connection: ActorRef = conn

            override def consumeFrom: Queue = Queue("testmsgidq")

            override def receive: Receive = {
                case AMQMessage(body, props, _) =>
                    probe.ref ! props
                    sender() ! body
            }
        })

        val cons2 = TestActorRef(new QueueConsumer {
            override def connection: ActorRef = conn

            override def consumeFrom: Queue = Queue("testmsgidrespq")

            override def receive: Receive = {
                case msg => probe2.ref ! msg
            }
        })

        val message = AMQMessage("hello", JMSMessageProperties(correlationID = None, replyTo = Some(Queue("testmsgidrespq"))))

        val probe3 = TestProbe()
        probe3.send(conn, SendMessage(Queue("testmsgidq"), message))

        probe3.expectMsg(SendAck)

        val sentMessageProps = probe.expectMsgType[JMSMessageProperties]

        sentMessageProps.messageID shouldBe defined

        val response = probe2.expectMsgType[AMQMessage]

        response.properties.correlationID.value should equal (sentMessageProps.messageID.value)

        system stop cons2

        system stop cons1

        closeConnection(conn)
    }

    it should "properly set ttl" in {
        val conn = getConnection

        val receiver = TestProbe()
        receiver.send(conn, ConsumeFromQueue("ttl"))
        receiver.expectMsgType[ConsumeSuccess]

        val sender = TestProbe()
        val now = System.currentTimeMillis()
        sender.send(conn, SendMessage(Queue("ttl"), AMQMessage("hi"), timeToLive = 5000))

        val msg = receiver.expectMsgType[AMQMessage]

        msg.properties.expiration should be ((now + 5000) +- 100)

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
        sender.send(conn, RequestMessage(Queue("ttl2"), AMQMessage("hi"), 1.second))
        sender.expectMsgType[Status.Failure]

        val msg = receiver.expectMsgType[AMQMessage]

        msg.properties.expiration should be ((now + 1000) +- 100)

        system stop receiver.ref

        closeConnection(conn)
    }

    class TestQueueConsumer(qName: String, val connection: ActorRef) extends QueueConsumer {
        def consumeFrom = Queue(qName)

        override def receive: Receive = {
            case AMQMessage(msg: String, _, _) => sender() ! msg.reverse
            case AMQMessage(msg: jl.Integer, _, _) => sender() ! Int.box(0 - msg.intValue())
            case msg: AMQMessage => sender() ! AMQMessage(msg.headers("replyWith"), headers = Map("original" -> msg.body))
        }
    }

    def doConsumeAndRespond(conn: ActorRef): Unit = {
        val cons = TestActorRef(new TestQueueConsumer("testQueueCons", conn))

        val probe = TestProbe()

        probe.send(conn, RequestMessage(Queue("testQueueCons"), AMQMessage("hello")))

        probe.expectMsgType[AMQMessage].body should equal ("olleh")

        probe.send(conn, RequestMessage(Queue("testQueueCons"), AMQMessage(3)))

        probe.expectMsgType[AMQMessage].body should equal (-3)

        probe.send(conn, RequestMessage(Queue("testQueueCons"), AMQMessage(true, headers = Map("replyWith" -> "reply"))))

        val msg = probe.expectMsgType[AMQMessage]
        msg.body should equal ("reply")
        msg.headers should contain key "original"
        msg.headers("original") should equal (true)

        probe watch cons
        cons ! PoisonPill

        probe.expectMsgType[Terminated]

        closeConnection(conn)
    }

    "A QueueConsumer" should "consume messages and allow responses" in doConsumeAndRespond(getConnection)

    it should "consume messages and allow responses with authentication" in {
        restartBroker(Some("user"), Some("pass"))

        val probe = TestProbe()

        probe.send(manager, GetAuthenticatedConnection(s"vm://$brokerName?create=false", "user", "pass"))

        val connEst = probe.expectMsgType[ConnectionEstablished]

        system stop probe.ref

        doConsumeAndRespond(connEst.connectionActor)

        restartBroker()
    }

    class TestOnewayQueueConsumer(qName: String, fwdTo: ActorRef, val connection: ActorRef) extends QueueConsumer with Oneway {
        def consumeFrom = Queue(qName)

        override def receive: Receive = {
            case msg => fwdTo forward msg
        }
    }

    it should "consume messages without creating a responder in oneway mode" in {
        val conn = getConnection

        val probe = TestProbe()

        val cons = TestActorRef(new TestOnewayQueueConsumer("testonewayq", probe.ref, conn))

        probe.send(conn, SendMessage(Queue("testonewayq"), AMQMessage("hi")))

        probe.expectMsg(SendAck)

        probe.expectMsgType[AMQMessage].bodyAs[String] should equal ("hi")
        // one-way consumer, who forwards the message to probe, forwards the sender that should be Actor.noSender as
        // opposed to an actor ref on a two-way consumer that sends replies over amq
        probe.sender().path should equal (cons.path.root / "deadLetters")

        system stop cons

        closeConnection(conn)
    }

    class TestOnewayQueueConsWithStatus(qName: String, fwdTo: ActorRef, connection: ActorRef)
        extends TestOnewayQueueConsumer(qName, fwdTo, connection) {
        override protected def receiveConsumeNotifications: Boolean = true
    }

    it should "send queue consumption status notifications" in {
        val conn = getConnection

        val probe = TestProbe()

        val cons = TestActorRef(new TestOnewayQueueConsWithStatus("teststatusnotifsq", probe.ref, conn))

        probe.expectMsg(ConsumeSuccess(Queue("teststatusnotifsq")))

        system stop cons

        closeConnection(conn)
    }

    class TestTopicConsumer(tName: String, val connection: ActorRef, probe: TestProbe) extends TopicConsumer {
        def consumeFrom = Topic(tName)

        override def receive: Receive = {
            case msg => probe.ref forward msg
        }
    }

    "A TopicConsumer" should "receive messages" in {
        val conn = getConnection

        val probe1 = TestProbe()
        val probe2 = TestProbe()

        val cons1 = TestActorRef(new TestTopicConsumer("testTopicCons", conn, probe1))
        val cons2 = TestActorRef(new TestTopicConsumer("testTopicCons", conn, probe2))

        Thread.sleep(250)

        producer.sendBody("embedded:topic:testTopicCons", "topic test")

        probe1.expectMsgType[AMQMessage].body should equal ("topic test")
        probe2.expectMsgType[AMQMessage].body should equal ("topic test")

        system stop probe1.ref
        system stop probe2.ref
        system stop cons1
        system stop cons2

        closeConnection(conn)
    }

    class TestOnewayProducer(val destination: Destination, val connection: ActorRef) extends Producer with Oneway

    "A Producer" should "send messages in oneway mode" in {
        val conn = getConnection

        val consumer = TestProbe()

        consumer.send(conn, ConsumeFromQueue("oneway"))
        consumer.expectMsgType[ConsumeSuccess]

        val prod = TestActorRef(new TestOnewayProducer(Queue("oneway"), conn))

        val probe2 = TestProbe()

        probe2.send(prod, "test")
        consumer.expectMsgType[AMQMessage].body should equal ("test")
        probe2.expectNoMessage(500.millis)

        system stop prod
        system stop consumer.ref

        closeConnection(conn)
    }

    class TestOnewayStatusProducer(val destination: Destination, val connection: ActorRef) extends Producer with Oneway {
        override protected def swallowSendStatus: Boolean = false
    }

    it should "send messages and return status in oneway mode" in {
        val conn = getConnection

        val consumer = TestProbe()

        consumer.send(conn, ConsumeFromQueue("oneway"))
        consumer.expectMsgType[ConsumeSuccess]

        val prod = TestActorRef(new TestOnewayStatusProducer(Queue("oneway"), conn))

        val probe2 = TestProbe()

        probe2.send(prod, "status test")
        consumer.expectMsgType[AMQMessage].body should equal ("status test")
        probe2.expectMsg(SendAck)

        system stop prod
        system stop consumer.ref

        closeConnection(conn)
    }

    class TestProducer(val destination: Destination, val connection: ActorRef) extends Producer

    it should "send/receive messages in twoway mode" in {
        val conn = getConnection

        val qcons = TestActorRef(new TestQueueConsumer("prodrrtest", conn))

        val prod = TestActorRef(new TestProducer(Queue("prodrrtest"), conn))

        val probe = TestProbe()

        probe.send(prod, "hello")

        probe.expectMsgType[AMQMessage].body should equal ("olleh")

        probe.send(prod, 3)

        probe.expectMsgType[AMQMessage].body should equal (-3)

        probe.send(prod, AMQMessage(true, headers = Map("replyWith" -> "reply")))

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
            case str: String => str.reverse
            case num: jl.Integer => 0 - num.intValue()
            case AMQMessage(_, _, headers) => AMQMessage(headers("newBody"))
            case _ => msg
        }
    }

    it should "transform outgoing messages" in {
        val conn = getConnection

        val qcons = TestActorRef(new TestQueueConsumer("prodrrtest", conn))

        val prod = TestActorRef(new TestTransformProducer(Queue("prodrrtest"), conn))

        val probe = TestProbe()

        probe.send(prod, "hello")

        probe.expectMsgType[AMQMessage].body should equal ("hello")

        probe.send(prod, 3)

        probe.expectMsgType[AMQMessage].body should equal (3)

        probe.send(prod, AMQMessage(true, headers = Map("newBody" -> "reply")))

        probe.expectMsgType[AMQMessage].body should equal ("ylper")

        system stop prod

        system stop qcons

        closeConnection(conn)
    }

    class TestTransformResponseProducer(val destination: Destination, val connection: ActorRef) extends Producer {
        override protected def transformResponse(msg: Any): Any = msg match {
            case AMQMessage(str: String, _, _) => str.reverse
            case AMQMessage(num: jl.Integer, _, _) => 0 - num.intValue()
            case _ => msg
        }
    }

    it should "transform replies" in {
        val conn = getConnection

        val cons = TestActorRef(new QueueConsumer {
            override def consumeFrom: Queue = Queue("xformReplies")

            override def connection: ActorRef = conn

            override def receive: Actor.Receive = {
                case msg => sender() ! msg
            }
        })

        val prod = TestActorRef(new TestTransformResponseProducer(Queue("xformReplies"), conn))

        val probe = TestProbe()

        probe.send(prod, "hello")

        probe.expectMsg("olleh")

        probe.send(prod, 12)

        probe.expectMsg(-12)

        probe.send(prod, Topic("ueo"))

        probe.expectMsgType[AMQMessage].body should equal (Topic("ueo"))

        system stop prod
        system stop cons

        closeConnection(conn)
    }

    "ReActiveMQ" should "time out sends while disconnected" in {
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
