/*
 * ConnectionActor.scala
 *
 * Updated: Jan 29, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.connection

import javax.jms.{Connection, Session}

import com.codemettle.reactivemq.ReActiveMQMessages.{CloseConnection, SendMessage}
import com.codemettle.reactivemq.connection.ConnectionFactoryActor.ConnectionException

import akka.actor._
import akka.pattern.pipe
import scala.concurrent.Future
import scala.util.control.Exception.ignoring

/**
 * @author steven
 *
 */
object ConnectionActor {
    def props(conn: Connection, sess: Session, connectionActor: ActorRef) = {
        Props(new ConnectionActor(conn, sess, connectionActor))
    }
}

class ConnectionActor(conn: Connection, protected val session: Session, sendRepliesAs: ActorRef)
    extends Actor with DestinationManager with ProducerManager with ActorLogging {
    import context.dispatcher

    override def postStop() = {
        super.postStop()

        ignoring(classOf[Exception])(session.close())
        ignoring(classOf[Exception])(conn.close())
    }

    private def routeFuture[T](to: ActorRef)(f: ⇒ Future[T]) = {
        f.pipeTo(to)(sendRepliesAs)
    }

    def receive = handleDestinationMessages orElse handleProducerMessages orElse {
        case m@SendMessage(dest, msg, timeout) ⇒
            routeFuture(sender()) {
                getProducer(dest) map (prod ⇒ prod send msg.jmsMessage)
            }

        case ConnectionException(e) ⇒
            log.error(e, "Exception; closing connection")
            context stop self

        case CloseConnection ⇒ self ! PoisonPill
    }
}
