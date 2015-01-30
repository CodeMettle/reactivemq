/*
 * ConsumerManager.scala
 *
 * Updated: Jan 29, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.connection

import javax.jms.{Destination => JMSDestination, _}

import com.codemettle.reactivemq.ReActiveMQMessages.ConsumerMessage
import com.codemettle.reactivemq.config.ReActiveMQConfig
import com.codemettle.reactivemq.connection.ConsumerManager._
import com.codemettle.reactivemq.model.Queue
import com.codemettle.reactivemq.model.Topic
import com.codemettle.reactivemq.model._

import akka.actor._
import akka.pattern.pipe
import scala.util.control.Exception.ignoring

/**
 * @author steven
 *
 */
object ConsumerManager {

    private class DestinationConsumer(session: Session, dest: Destination, protected val sendRepliesAs: ActorRef)
        extends Actor with SendRepliesAs with ActorLogging {
        import context.dispatcher

        private val config = ReActiveMQConfig(context.system)

        private var consumer = Option.empty[MessageConsumer]
        private var subscribers = Set.empty[ActorRef]

        private var idleTimer = Option.empty[Cancellable]

        override def preStart() = {
            super.preStart()

            context.parent ! GetDestAndConsumers(dest)
        }

        override def postStop() = {
            super.postStop()

            consumer foreach (cons ⇒ {
                ignoring(classOf[Exception])(cons.close())
            })

            idleTimer foreach (_.cancel())
        }

        private def checkIdle() = {
            idleTimer foreach (_.cancel())
            if (subscribers.isEmpty)
                idleTimer = Some(context.system.scheduler.scheduleOnce(config.consumerIdleTimeout, self, PoisonPill))
        }

        private def subscribe(subs: Iterable[ActorRef]): Unit = {
            subs foreach (sub ⇒ {
                if (!subscribers.contains(sub)) {
                    context watch sub
                    subscribers += sub
                    log.debug(s"Subscribing {} to {}", sub, dest)
                }
            })
        }

        def receive = {
            case DestAndConsumers(jmsDest, consumers) ⇒
                val cons = session createConsumer jmsDest
                cons setMessageListener new MsgListener(self)
                consumer = Some(cons)
                subscribe(consumers)
                checkIdle()

            case AddSubscriber ⇒
                subscribe(Iterable(sender()))
                checkIdle()

            case Terminated(act) ⇒
                if (subscribers contains act) {
                    subscribers -= act
                    checkIdle()
                }

            case msg: AMQMessage ⇒ subscribers foreach (_ tellAs msg)
        }
    }

    private object DestinationConsumer {
        def props(session: Session, dest: Destination, sendRepliesAs: ActorRef) = {
            Props(new DestinationConsumer(session, dest, sendRepliesAs))
        }
    }

    private class MsgListener(sendTo: ActorRef) extends MessageListener {
        override def onMessage(message: Message): Unit = {
            sendTo ! (AMQMessage from message)
        }
    }

    private[connection] case class ConsumerTerminated(act: ActorRef)

    private case object AddSubscriber
    private case class GetDestAndConsumers(dest: Destination)
    private case class DestAndConsumers(jmsDest: JMSDestination, consumers: Set[ActorRef])
}

trait ConsumerManager extends Actor {
    this: DestinationManager with SendRepliesAs with ActorLogging ⇒
    import context.dispatcher

    protected def session: Session

    private var consumerActors = Map.empty[Destination, ActorRef]
    private var consumers = Map.empty[Destination, Set[ActorRef]]

    private def getSingleConsumer(forDest: Destination): ActorRef = {
        consumerActors.getOrElse(forDest, {
            val name = (forDest match {
                case _: Queue ⇒ "queue-"
                case _: Topic ⇒ "topic-"
                case _: TempQueue ⇒ "tempqueue-"
                case _: TempTopic ⇒ "temptopic-"
            }) + forDest.name

            val act = context.actorOf(DestinationConsumer.props(session, forDest, sendRepliesAs), name)
            context watch act
            consumerActors += (forDest → act)
            act
        })
    }

    protected def handleConsumerMessages: Receive = {
        case Terminated(act) if consumerActors.values.exists(_ == act) ⇒
            log.debug("{} shut down due to idle timeout", act)
            consumerActors find (_._2 == act) foreach (e ⇒ consumerActors -= e._1)

        case ConsumerTerminated(act) ⇒
            consumers = (Map.empty[Destination, Set[ActorRef]] /: consumers) {
                case (acc, (dest, subs)) ⇒
                    val newSet = subs - act
                    if (newSet.nonEmpty)
                        acc + (dest → newSet)
                    else
                        acc
            }

        case cm: ConsumerMessage ⇒
            val newSet = consumers.getOrElse(cm.destination, Set.empty) + sender()
            consumers += (cm.destination → newSet)

            getSingleConsumer(cm.destination) forward AddSubscriber

        case GetDestAndConsumers(dest) ⇒
            val cons = consumers.getOrElse(dest, Set.empty)
            getDestination(dest) map (d ⇒ DestAndConsumers(d, cons)) pipeTo sender()
    }
}
