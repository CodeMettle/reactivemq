/*
 * ConnectionFactory.scala
 *
 * Updated: Feb 6, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.activemq

import java.{io => jio, util => ju}
import javax.jms

import org.apache.activemq.ActiveMQConnectionFactory

import com.codemettle.reactivemq.activemq.ConnectionFactory.{Connection, ConnectionKey}
import com.codemettle.reactivemq.config.ReActiveMQConfig
import com.codemettle.reactivemq.model.{TempQueue, TempTopic}
import com.codemettle.reactivemq.{DestinationCreator, MessageCreator}

import scala.util.control.Exception.ignoring
import scala.util.control.NonFatal

/**
 * @author steven
 *
 */
private[reactivemq] object ConnectionFactory {
    def apply(key: ConnectionKey, config: ReActiveMQConfig) = new ConnectionFactory(key, config)

    private[reactivemq] case class ConnectionKey(brokerUrl: String, userAndPass: Option[(String, String)], staticName: Option[String])

    case class Connection(jmsConn: jms.Connection, jmsSess: jms.Session, owner: ConnectionFactory)
                         (implicit val mc: MessageCreator) extends DestinationCreator with MessageCreator {
        def setExceptionListener(listener: (jms.JMSException) => Unit): Unit = {
            jmsConn.setExceptionListener(new jms.ExceptionListener {
                override def onException(exception: jms.JMSException): Unit = listener(exception)
            })
        }

        override def createEmptyMessage: jms.Message = mc.createEmptyMessage

        override def createTextMessage(text: String): jms.TextMessage = mc.createTextMessage(text)

        override def createObjectMessage(obj: jio.Serializable): jms.ObjectMessage = mc.createObjectMessage(obj)

        override def createBytesMessage(data: => Array[Byte]): jms.BytesMessage = mc.createBytesMessage(data)

        def createConsumer(dest: jms.Destination): jms.MessageConsumer = jmsSess createConsumer dest

        def createProducer(dest: jms.Destination): jms.MessageProducer = jmsSess createProducer dest

        def createTemporaryQueue: TempQueue = TempQueue(jmsSess.createTemporaryQueue())

        def createTemporaryTopic: TempTopic = TempTopic(jmsSess.createTemporaryTopic())

        override def createQueue(name: String): jms.Queue = jmsSess createQueue name

        override def createTopic(name: String): jms.Topic = jmsSess createTopic name

        def close(swallowExceptions: Boolean = true): Unit = {
            owner.closeConnection(this, swallowExceptions)
        }
    }

    private[ConnectionFactory] implicit class RichAMQConnFact(val factory: ActiveMQConnectionFactory) extends AnyVal {
        def configureTrustedPackages(config: ReActiveMQConfig): Unit = {
            import java.{lang => jl}

            import scala.util.Try

            // allow this code to run on older versions of activemq by using reflection
            val factClass = factory.getClass
            Try(factClass.getDeclaredMethod("setTrustAllPackages", jl.Boolean.TYPE)).toOption foreach { method =>
              method.invoke(factory, Boolean box config.trustAllPackages)
            }

            if (config.trustedPackages.nonEmpty) {
                Try(factClass.getDeclaredMethod("setTrustedPackages", classOf[ju.List[String]])).toOption foreach { method =>
                  method.invoke(factory, ju.Arrays.asList(config.trustedPackages: _*))
                }
            }
        }
    }
}

private[reactivemq] class ConnectionFactory(key: ConnectionKey, config: ReActiveMQConfig) {
    import com.codemettle.reactivemq.activemq.ConnectionFactory.RichAMQConnFact

    private val factory = key.userAndPass.fold(new ActiveMQConnectionFactory(key.brokerUrl))(
        uandp => new ActiveMQConnectionFactory(uandp._1, uandp._2, key.brokerUrl))

    factory.configureTrustedPackages(config)

    private var connections = Set.empty[Connection]

    private def storeConnection(conn: Connection): Unit = synchronized {
        connections += conn
    }

    private[activemq] def removeConnection(conn: Connection): Unit = synchronized {
        connections -= conn
    }

    private[activemq] def closeConnection(connection: Connection, swallowExceptions: Boolean): Unit = {
        if (swallowExceptions) {
            ignoring(classOf[Exception])(connection.jmsSess.close())
            ignoring(classOf[Exception])(connection.jmsConn.close())
        } else {
            connection.jmsSess.close()
            connection.jmsConn.close()
        }

        removeConnection(connection)
    }

    def createConnection(): Connection = {
        val jmsConn = factory.createConnection()

        try {
            jmsConn.start()

            val jmsSess = jmsConn.createSession(false, jms.Session.AUTO_ACKNOWLEDGE)

            val conn = Connection(jmsConn, jmsSess, this)

            storeConnection(conn)

            conn
        } catch {
            case NonFatal(e) =>
                ignoring(classOf[Exception])(jmsConn.close())
                throw e
        }
    }

    private[reactivemq] def cleanup(): Unit = {
        while (connections.nonEmpty) {
            connections.headOption foreach (c => closeConnection(c, swallowExceptions = true))
        }
    }

    override def toString: String = {
        val authStr = key.userAndPass.fold("")(e => s", user=${e._1}")
        val staticNameStr = key.staticName.fold("")(n => s", staticName=$n")
        s"ConnectionFactory(${key.brokerUrl}$authStr$staticNameStr)"
    }
}
