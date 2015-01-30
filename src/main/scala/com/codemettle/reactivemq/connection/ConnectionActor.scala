/*
 * ConnectionActor.scala
 *
 * Updated: Jan 30, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.connection

import javax.jms.{Connection, Session}

import com.codemettle.reactivemq.ReActiveMQMessages.{RequestMessage, CloseConnection, SendMessage}
import com.codemettle.reactivemq.connection.ConnectionFactoryActor.ConnectionException
import com.codemettle.reactivemq.connection.requestreply.{RequestReplyActor, TempQueueReplyManager}
import com.codemettle.reactivemq.model.TempQueue

import akka.actor._
import akka.util.Helpers
import scala.util.{Failure, Success, Try}
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

class ConnectionActor(conn: Connection, protected val session: Session, protected val sendRepliesAs: ActorRef)
    extends Actor with DestinationManager with ProducerManager with ConsumerManager with SendRepliesAs with ActorLogging {
    import context.dispatcher

    private var tempQueueReplyMan = Option.empty[(ActorRef, TempQueue)]

    private val rraNamer = Iterator from 0 map (i ⇒ s"requestreply${Helpers.base64(i)}")

    override def postStop() = {
        super.postStop()

        ignoring(classOf[Exception])(session.close())
        ignoring(classOf[Exception])(conn.close())
    }

    private def getTQRM: Try[(ActorRef, TempQueue)] = {
        tempQueueReplyMan.fold(Try {
            val tempQueue = TempQueue create session
            val act = context.actorOf(TempQueueReplyManager.props(tempQueue, self), "tempQueueReplyManager")
            tempQueueReplyMan = Some(act → tempQueue)
            act → tempQueue
        })(Success(_))
    }

    def receive = handleDestinationMessages orElse handleProducerMessages orElse handleConsumerMessages orElse {
        case SendMessage(dest, msg, ttl, _) ⇒ routeFutureFromSRA(sender()) {
            val props = msg.properties
            getProducer(dest) map (prod ⇒ prod.send(msg.jmsMessage(session), props.deliveryMode, props.priority, ttl))
        }

        case rm: RequestMessage ⇒ getTQRM match {
            case Failure(t) ⇒ sender() tellFromSRA Status.Failure(t)

            case Success((tqrm, tempQueue)) ⇒
                val rra = context.actorOf(RequestReplyActor.props(sender(), tqrm, tempQueue, self, sendRepliesAs),
                    rraNamer.next())

                rra forward rm
        }

        case ConnectionException(e) ⇒
            log.error(e, "Exception; closing connection")
            context stop self

        case CloseConnection ⇒ self ! PoisonPill
    }
}
