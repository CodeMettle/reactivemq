ReActiveMQ
==========

An [ActiveMQ](http://activemq.apache.org) client built on [Akka](http://akka.io)
[![Build Status](https://travis-ci.org/CodeMettle/reactivemq.svg?branch=master)](https://travis-ci.org/CodeMettle/reactivemq)

The goal of ReActiveMQ is to provide an interface to ActiveMQ messaging that will feel familiar to Akka developers. At this point it is a fairly low-level connection-oriented interface that can consume AMQ queues and topics, send messages to endpoints, and do request-reply messaging, Camel-style, using temporary queues. More features will be added as necessary / requested.

#### What about [akka-camel](http://doc.akka.io/docs/akka/snapshot/scala/camel.html)?

Camel provides a plethora of components, and can be used with JMS/ActiveMQ, and if you need it then by all means use it. If, however, you're bringing in akka-camel, camel-core, and activemq-camel simply to do messaging, dealing with Camel's configuration (string arguments on endpoints, consumers, producers, etc), and adding another even-more blocking layer on top of ActiveMQ, then ReActiveMQ may be for you.

#### What about (insert-jms-provider-here)?

ReActiveMQ only uses JMS code, except to create the `ActiveMQConnectionFactory` and `ActiveMQMessage`s. If desired, ActiveMQ support can easily be moved into an optional library and other providers could be added. Make a feature request!


Import
------

ReActiveMQ depends on akka-actor and activemq-client, but the dependencies are marked as `Provided`, so the end user must specify them as dependencies.

#### Add Dependencies:

sbt:

```scala
libraryDependencies ++= Seq(
    "com.codemettle.reactivemq" %% "reactivemq" % "1.0.1",
    "org.apache.activemq" % "activemq-client" % "version",
    "com.typesafe.akka" %% "akka-actor" % "version"
)
```

Maven:

```xml
<dependency>
    <groupId>com.codemettle.reactivemq</groupId>
    <artifactId>reactivemq</artifactId>
    <version>1.0.1</version>
</dependency>
<dependency>
    <groupId>org.apache.activemq</groupId>
    <artifactId>activemq-client</artifactId>
    <version>version</version>
</dependency>
<dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-actor</artifactId>
    <version>version</version>
</dependency>
```


Usage
-----

##### Create a connection:

```scala
import com.codemettle.reactivemq._
import com.codemettle.reactivemq.ReActiveMQMessages._
import com.codemettle.reactivemq.model._

ReActiveMQExtension(system).manager ! GetConnection("nio://esb:61616")

// connectionActor replies with ConnectionEstablished(request, connectionActor) or
//  ConnectionFailed(request, reason)
```

Connections can be named:

```scala
ReActiveMQExtension(system).manager ! GetConnection("nio://esb:61616", Some("myName"))

/* .... */

val connSel = context.actorSelection("/system/reActiveMQ/myName")
```

Connections can be authenticated:

```scala
ReActiveMQExtension(system).manager ! GetAuthenticatedConnection("nio://esb:61616", "user", "pass")
```

##### Close connections:

```scala
connectionActor ! CloseConnection
```

The `connectionActor` will stay alive and valid, maintaining established subscriptions, until `reactivemq.idle-connection-factory-shutdown` time has elapsed. A new `GetConnection` will re-use the `connectionActor` until the idle shutdown, after which a new connection will be created in response to a `GetConnection`. If a connection is reestablished before the timeout ends, then any actors that are still alive and had previously subscribed to connection status or consumed from queues/topics will be re-subscribed.

##### Consume from AMQ:

```scala
connectionActor ! ConsumeFromQueue("queueName") // shared defaults to true, but can be overridden
connectionActor ! ConsumeFromTopic("topicName") // shared defaults to false, but can be overridden
connectionActor ! Consume(Queue("queueName"), sharedConsumer = false)
connectionActor ! Consume(Topic("topicName"), sharedConsumer = true)

// sender of the consume message receives AMQMessage(body, properties, headers) messages
// for non-shared, the sender additionally receives status messages (see below)
```

ReActiveMQ also has akka-camel-ish Consumer actors:

```scala
// it's important that connection and consumeFrom are defined as defs and not vals, unless in the constructor

class MyTopicConsumer(val connection: ActorRef) extends TopicConsumer {
    def consumeFrom = Topic("mytopic")

    def receive = {
        case msg: AMQMessage =>
    }
}

class MyQueueConsumer(val consumeFrom: Queue) extends QueueConsumer {
    def connection = ReActiveMQExtension(context.system) autoConnects "myconn"

    def receive = {
        case msg: AMQMessage =>
    }
}
```

###### Shared consumers

By default, consuming from topics opens up 1 consumer in ActiveMQ, and broadcasts all received messages to all subscribers, but consumers established with `sharedConsumer` set to false will open multiple subscribers in ActiveMQ.

By default, consuming from queues opens up 1 consumer per subscriber in ActiveMQ, but can be shared by setting `sharedConsumer` to `true` (which, of course, makes all consumers receive all messages instead of ActiveMQ's behavior of load balancing between multiple queue consumers).

###### Dedicated (non-shared) consumers

Any consumer created as dedicated (`sharedConsumer = false`) gets status messages about the consumer state, and, additionally, is allowed to end its dedicated subscriptions without stopping (useful for cleanly shutting down).

Upon subscribing, the subscriber will receive a `ConsumeSuccess(Destination)` message, or a `ConsumeFailed(Destination, Throwable)` message. If auto-reconnect is enabled, and the connection is interrupted and reestablished, then ReActiveMQ will attempt to reestablish all consumers (shared and dedicated alike), and dedicated consumers will receive new `ConsumeSuccess`/`ConsumeFailed` messages.

To stop a dedicated consumer subscription, send an `EndConsumption(Destination)` message to the connection actor from the subscribing actor. When the consumer is closed, the subscriber will be sent a `ConsumptionEnded(Destination)` message.

##### Send messages:

```scala
connectionActor ! SendMessage(Queue("queueName"), AMQMessage(body))

// sender receives SendAck, or a Status.Failure(reason)
```

ReActiveMQ also has an akka-camel-ish Producer actor:

```scala
class MyProducer(val destination: Destination) extends Producer with Oneway

class My2ndProducer extends Producer {
    def destination = Topic("blah")

    override protected def oneway = true
}

class My3rdProducer extends Producer with Oneway {
    def destination = Queue("q")

    override protected def transformOutgoingMessage(msg: Any) = msg match {
        case str: String => str.reverse
        case _ => msg
    }
}
```

##### Request reply:

```scala
// or use ask/? to get a future of the response
connectionActor ! RequestMessage(Queue("name"), AMQMessage(body))

// sender receives the response, or a Status.Failure(reason)

// ReActiveMQ also has an akka-camel-ish Producer actor

class MyProducer extends Producer {
    def destination = Queue("aQueue")
}

val prod = context.actorOf(Props[MyProducer])

(prod ? "respond to this").mapTo[AMQMessage]
(prod ? AMQMessage("body", JMSMessageProperties().copy(`type` = Some("myJmsType")))).mapTo[AMQMessage]
```

##### Connection status notifications:

```scala
connectionActor ! SubscribeToConnectionStatus

def receive = {
    case ConnectionInterrupted(connectionActor: ActorRef) =>

    case ConnectionReestablished(connectionActor: ActorRef) =>
}

// ConnectionReestablished messages will also be sent from standard connections newly
//  established with GetConnection that had previously been closed, but not closed
//  long enough for connection cleanup
```

##### Auto-connect:

Configure autoconnects in `application.conf`:

```
reactivemq {
  autoconnect {
    myConn.address = "nio://blah:61616"
    secondConn {
      address = "vm://localhost"
      username = user
      password = pass
    }
  }
}
```

And then connections are available on the extension:

```scala
val connectionActor = ReActiveMQExtension(system) autoConnects "myConn"

// or perhaps

context.actorSelection("/system/reActiveMQ/secondConn") ! Identify("")
// standard ActorIdentity response with the connectionActor
```

Plans
-----

* More examples for using the library; more information in README
* Async sending
* Needs unit tests
* Support akka-streams, consume queues/topics as Sources, and send messages as Sinks
* Easier actor traits, like akka-camel, to create Consumers
* ???


License
-------

[Apache License, 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)


Changelog
---------

* **1.0.2**
  * Support JMS `BytesMessage`s (by [@barredijkstra](https://github.com/barredijkstra))
* **1.0.1**
  * Remove Spray dependency
  * Upgrade dependencies
  * Add support for Scala 2.12
  * Fix Travis configuration
  * Thanks to [@barredijkstra](https://github.com/barredijkstra) for much of this
* **1.0.0**
  * Upgrade Akka to 2.4
  * Drop support for Scala 2.10
  * Compile with Java8
* **0.5.5**
  * Oops - request/reply `Producer` wasn't honoring the `requestTimeout` parameter 
* **0.5.4**
  * Oops - was treating empty/null credentials as valid and passing them on to AMQ
* **0.5.3**
  * Allow automatic connections to be authenticated; the format in application.conf has changed but old configurations are still loaded correctly
* **0.5.2**
  * Load ReActiveMQ as a system actor
  * Fix issue in Request-Reply wrt accessing actor state from a future leading to NPEs
* **0.5.1**
  * SendMessage messages are now replied to with a SendAck case object instead of Unit
  * AMQMessage.bodyAs method (like CamelMessage.bodyAs except no conversion tried, just a ClassCastException if the type is wrong)
  * Support jms.Message (no body, AMQMessage's body field will be null)
  * QueueConsumer can be in consume-only mode, saves an actor creation for each message, but replying to sender() goes to deadLetters
  * AMQMessage.camelHeaders - yields a map with any headers on the message, plus all the JMSMessageProperty properties in the map with "JMS" prefix (allows easier transition from Camel)
  * Producer.transformResponse can be overridden like akka-camel's Producer, may get changed in a future release to return an Option[(Any) => Any] so subclasses can return None and save an actor creation.
  * Use the request message's MessageID as the response's CorrelationID if the request had no CorrelationID in request/reply mode
* **0.5.0**
  * Initial Release
  * Support for major JMS operations in an actor+message passing interface
  * Support for akka-camel-ish Consumer and Producer actors
  * Support for building immutable objects that represent JMS messages in a Scala-ish manner
  * Specify connections to initiate at Extension load time statically in application.conf

Credits
-------
* Authored by [@codingismy11to7](https://github.com/codingismy11to7) for [@CodeMettle](https://github.com/CodeMettle)
