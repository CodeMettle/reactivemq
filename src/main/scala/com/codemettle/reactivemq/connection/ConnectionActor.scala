/*
 * ConnectionActor.scala
 *
 * Updated: Jan 28, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.connection

import javax.jms.{JMSException, ExceptionListener, Session, Connection}

import com.codemettle.reactivemq.ReActiveMQMessages.CloseConnection
import com.codemettle.reactivemq.connection.ConnectionActor.{Listener, ConnectionException}

import akka.actor._
import scala.util.control.Exception.ignoring

/**
 * @author steven
 *
 */
object ConnectionActor {
    def props(conn: Connection, sess: Session) = {
        Props(new ConnectionActor(conn, sess))
    }

    private case class ConnectionException(e: JMSException)

    private class Listener(act: ActorRef) extends ExceptionListener {
        override def onException(exception: JMSException): Unit = act ! ConnectionException(exception)
    }
}

class ConnectionActor(conn: Connection, session: Session) extends Actor with ActorLogging {
    override def preStart() = {
        super.preStart()

        conn setExceptionListener new Listener(self)
    }

    override def postStop() = {
        ignoring(classOf[Exception])(session.close())
        ignoring(classOf[Exception])(conn.close())
    }

    def receive = {
        case ConnectionException(e) ⇒
            log.error(e, "error")

        case CloseConnection ⇒ self ! PoisonPill
    }
}
