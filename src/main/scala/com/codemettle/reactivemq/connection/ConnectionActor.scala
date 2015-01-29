/*
 * ConnectionActor.scala
 *
 * Updated: Jan 28, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.connection

import javax.jms.{Connection, ExceptionListener, JMSException, Session}

import com.codemettle.reactivemq.ReActiveMQMessages.{CloseConnection, SendMessage}
import com.codemettle.reactivemq.connection.ConnectionActor.{ConnectionException, Listener}

import akka.actor._
import akka.pattern.pipe
import scala.util.control.Exception.ignoring

/**
 * @author steven
 *
 */
object ConnectionActor {
    def props(conn: Connection, sess: Session, connectionActor: ActorRef) = {
        Props(new ConnectionActor(conn, sess, connectionActor))
    }

    private case class ConnectionException(e: JMSException)

    private class Listener(act: ActorRef) extends ExceptionListener {
        override def onException(exception: JMSException): Unit = act ! ConnectionException(exception)
    }
}

class ConnectionActor(conn: Connection, protected val session: Session, sendRepliesAs: ActorRef)
    extends Actor with DestinationManager with ProducerManager with ActorLogging {
    import context.dispatcher

    override def preStart() = {
        super.preStart()

        conn setExceptionListener new Listener(self)
    }

    override def postStop() = {
        super.postStop()

        ignoring(classOf[Exception])(session.close())
        ignoring(classOf[Exception])(conn.close())
    }

    def receive = handleDestinationMessages orElse handleProducerMessages orElse {
        case m@SendMessage(dest, msg, timeout) ⇒
            println(s"msgid: ${msg.jmsMessage.getJMSMessageID}")
            (getProducer(dest) map (prod ⇒ prod send msg.jmsMessage)).pipeTo(sender())(sendRepliesAs)

        case ConnectionException(e) ⇒
            log.error(e, "Exception; closing connection")
            context stop self

        case CloseConnection ⇒ self ! PoisonPill
    }
}
