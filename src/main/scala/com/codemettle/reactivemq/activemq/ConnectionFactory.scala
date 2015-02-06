/*
 * ConnectionFactory.scala
 *
 * Updated: Feb 6, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.activemq

import javax.jms

import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.command.{ActiveMQObjectMessage, ActiveMQTextMessage}
import java.{io ⇒ jio}
import com.codemettle.reactivemq.activemq.ConnectionFactory.{Connection, ConnectionKey}
import com.codemettle.reactivemq.model.{TempTopic, TempQueue}

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

    case class Connection(jmsConn: jms.Connection, jmsSess: jms.Session, owner: ConnectionFactory) {
        def setExceptionListener(listener: jms.JMSException ⇒ Unit) = {
            jmsConn.setExceptionListener(new jms.ExceptionListener {
                override def onException(exception: jms.JMSException): Unit = listener(exception)
            })
        }

        def createConsumer(dest: jms.Destination) = jmsSess createConsumer dest

        def createProducer(dest: jms.Destination) = jmsSess createProducer dest

        def createTemporaryQueue: TempQueue = TempQueue(jmsSess.createTemporaryQueue())

        def createTemporaryTopic: TempTopic = TempTopic(jmsSess.createTemporaryTopic())

        def createQueue(name: String): jms.Queue = jmsSess createQueue name

        def createTopic(name: String): jms.Topic = jmsSess createTopic name

        def close(swallowExceptions: Boolean = true) = {
            owner.closeConnection(this, swallowExceptions)
        }

        def createTextMessage(text: String): jms.TextMessage = {
            val msg = new ActiveMQTextMessage
            msg setText text
            msg
        }

        def createObjectMessage(obj: jio.Serializable): jms.ObjectMessage = {
            val msg = new ActiveMQObjectMessage
            msg setObject obj
            msg
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
