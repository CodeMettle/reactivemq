/*
 * ConnectionFactory.scala
 *
 * Updated: Feb 6, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.activemq

import java.{io ⇒ jio}
import javax.jms
import javax.jms.{BytesMessage, ObjectMessage, TextMessage}

import com.codemettle.reactivemq.activemq.ConnectionFactory.{Connection, ConnectionKey}
import com.codemettle.reactivemq.model.{TempQueue, TempTopic}
import com.codemettle.reactivemq.{DestinationCreator, MessageCreator}
import org.apache.activemq.ActiveMQConnectionFactory

import scala.util.control.Exception.ignoring
import scala.util.control.NonFatal

/**
 * @author steven
 *
 */
private[reactivemq] object ConnectionFactory {
    def apply(key: ConnectionKey) = {
        new ConnectionFactory(key)
    }

    private[reactivemq] case class ConnectionKey(brokerUrl: String, userAndPass: Option[(String, String)], staticName: Option[String])

    case class Connection(jmsConn: jms.Connection, jmsSess: jms.Session, owner: ConnectionFactory)
                         (implicit val mc: MessageCreator) extends DestinationCreator with MessageCreator {
        def setExceptionListener(listener: jms.JMSException ⇒ Unit) = {
            jmsConn.setExceptionListener(new jms.ExceptionListener {
                override def onException(exception: jms.JMSException): Unit = listener(exception)
            })
        }

        override def createTextMessage(text: String): TextMessage = mc.createTextMessage(text)

        override def createObjectMessage(obj: jio.Serializable): ObjectMessage = mc.createObjectMessage(obj)

        override def createBytesMessage(data: ⇒ Array[Byte]): BytesMessage = mc.createBytesMessage(data)

        def createConsumer(dest: jms.Destination) = jmsSess createConsumer dest

        def createProducer(dest: jms.Destination) = jmsSess createProducer dest

        def createTemporaryQueue: TempQueue = TempQueue(jmsSess.createTemporaryQueue())

        def createTemporaryTopic: TempTopic = TempTopic(jmsSess.createTemporaryTopic())

        override def createQueue(name: String): jms.Queue = jmsSess createQueue name

        override def createTopic(name: String): jms.Topic = jmsSess createTopic name

        def close(swallowExceptions: Boolean = true) = {
            owner.closeConnection(this, swallowExceptions)
        }
    }
}

private[reactivemq] class ConnectionFactory(key: ConnectionKey) {
    private val factory = key.userAndPass.fold(new ActiveMQConnectionFactory(key.brokerUrl))(
        uandp ⇒ new ActiveMQConnectionFactory(uandp._1, uandp._2, key.brokerUrl))

    private var connections = Set.empty[Connection]

    private def storeConnection(conn: Connection) = synchronized {
        connections += conn
    }

    private[activemq] def removeConnection(conn: Connection) = synchronized {
        connections -= conn
    }

    private[activemq] def closeConnection(connection: Connection, swallowExceptions: Boolean) = {
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
            case NonFatal(e) ⇒
                ignoring(classOf[Exception])(jmsConn.close())
                throw e
        }
    }

    private[reactivemq] def cleanup() = {
        while (connections.nonEmpty) {
            connections.headOption foreach (c ⇒ closeConnection(c, swallowExceptions = true))
        }
    }

    override def toString = {
        val authStr = key.userAndPass.fold("")(e ⇒ s", user=${e._1}")
        val staticNameStr = key.staticName.fold("")(n ⇒ s", staticName=$n")
        s"ConnectionFactory(${key.brokerUrl}$authStr$staticNameStr)"
    }
}
