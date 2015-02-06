/*
 * ConsumerManager.scala
 *
 * Updated: Feb 6, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.connection

import javax.jms

import com.codemettle.reactivemq.ReActiveMQMessages._
import com.codemettle.reactivemq.activemq.ConnectionFactory.Connection
import com.codemettle.reactivemq.config.ReActiveMQConfig
import com.codemettle.reactivemq.connection.ConsumerManager._
import com.codemettle.reactivemq.model._

import akka.actor._
import akka.event.Logging
import akka.pattern.pipe
import akka.util.Helpers
import scala.util.{Failure, Success, Try}
import scala.util.control.Exception.ignoring

/**
 * @author steven
 *
 */
object ConsumerManager {

    private trait ConsumerActor extends Actor with ActorLogging {
        protected def connection: Connection
        protected def dest: Destination

        private def config = ReActiveMQConfig(context.system)
        private val queueSubLogLevel = if (config.logConsumers) Logging.InfoLevel else Logging.DebugLevel

        protected def logConsumer(sub: ActorRef, unsubscribe: Boolean) = {
            val logLevel = dest match {
                case _: Queue ⇒ queueSubLogLevel
                case _ ⇒ Logging.DebugLevel
            }

            if (unsubscribe)
                log.log(logLevel, "Unsubscribed {} from {}", sub, dest)
            else
                log.log(logLevel, "Subscribed {} to {}", sub, dest)
        }

        protected var consumer = Option.empty[jms.MessageConsumer]

        protected val msgListener = new MsgListener(self)

        protected def silentlyClose() = {
            consumer foreach (cons ⇒ {
                ignoring(classOf[Exception])(cons.close())
            })
        }

        protected def createConsumer(jmsDest: jms.Destination) = {
            val cons = connection createConsumer jmsDest
            cons setMessageListener msgListener
            consumer = Some(cons)
        }
    }

    private class SharedDestinationConsumer(protected val connection: Connection, protected val dest: Destination,
                                            protected val sendRepliesAs: ActorRef)
        extends Actor with SendRepliesAs with ConsumerActor with ActorLogging {

        private var subscribers = Set.empty[ActorRef]

        override def preStart() = {
            super.preStart()

            context.parent ! GetDestAndConsumers(dest)
        }

        override def postStop() = {
            super.postStop()

            silentlyClose()
        }

        private def subscribe(subs: Iterable[ActorRef]): Unit = {
            subs foreach (sub ⇒ {
                if (!subscribers.contains(sub)) {
                    context watch sub
                    subscribers += sub

                    logConsumer(sub, unsubscribe = false)
                }
            })
        }

        def receive = {
            case DestAndConsumers(jmsDest, consumers) ⇒
                createConsumer(jmsDest)
                subscribe(consumers)

            case AddSubscriber ⇒ subscribe(Iterable(sender()))

            case Terminated(act) ⇒
                if (subscribers contains act)
                    logConsumer(act, unsubscribe = true)

                subscribers -= act
                if (subscribers.isEmpty)
                    context stop self

            case msg: AMQMessage ⇒ subscribers foreach (_ tellFromSRA msg)

            case TranslateError(t, msg) ⇒ log.error(t, "Couldn't decode {}", msg)
        }
    }

    private object SharedDestinationConsumer {
        def props(conn: Connection, dest: Destination, sendRepliesAs: ActorRef) = {
            Props(new SharedDestinationConsumer(conn, dest, sendRepliesAs))
        }
    }

    private class DedicatedDestinationConsumer(protected val connection: Connection, protected val dest: Destination,
                                               subscriber: ActorRef, protected val sendRepliesAs: ActorRef)
        extends Actor with SendRepliesAs with ConsumerActor with ActorLogging {
        override def preStart() = {
            super.preStart()

            context watch subscriber
        }

        override def postStop() = {
            super.postStop()

            consumer foreach (_ ⇒ logConsumer(subscriber, unsubscribe = true))

            // cleanup in case the subscriber shutdown without ending subscription
            silentlyClose()
        }

        def receive = {
            case DestAndConsumers(jmsDest, _) ⇒ consumer match {
                case Some(_) ⇒ subscriber ! ConsumeSuccess(dest)
                case None ⇒ Try {
                    createConsumer(jmsDest)
                } match {
                    case Success(_) ⇒ self ! ConsumeSuccess(dest)
                    case Failure(t) ⇒ self ! ConsumeFailed(dest, t)
                }
            }

            case ConsumeSuccess(_) ⇒
                subscriber ! ConsumeSuccess(dest)
                logConsumer(subscriber, unsubscribe = false)

            case ConsumeFailed(_, t) ⇒
                log.error(t, "Error consuming from {} for {}", dest, subscriber)
                subscriber ! ConsumeFailed(dest, t)
                context stop self

            case _: EndConsumption ⇒
                silentlyClose()
                subscriber ! ConsumptionEnded(dest)
                context stop self

            case Terminated(`subscriber`) ⇒ context stop self

            case msg: AMQMessage ⇒ subscriber tellFromSRA msg

            case TranslateError(t, msg) ⇒ log.error(t, "Couldn't decode {}", msg)
        }
    }

    private object DedicatedDestinationConsumer {
        def props(conn: Connection, dest: Destination, subscriber: ActorRef, sendRepliesAs: ActorRef) = {
            Props(new DedicatedDestinationConsumer(conn, dest, subscriber, sendRepliesAs))
        }
    }

    private case class TranslateError(t: Throwable, message: jms.Message)

    private class MsgListener(sendTo: ActorRef) extends jms.MessageListener {
        override def onMessage(message: jms.Message): Unit = {
            sendTo ! (Try(AMQMessage from message) recover {
                case t ⇒ TranslateError(t, message)
            }).get
        }
    }

    private[connection] case class ConsumerTerminated(act: ActorRef)

    private case object AddSubscriber
    private case class GetDestAndConsumers(dest: Destination)
    private case class DestAndConsumers(jmsDest: jms.Destination, consumers: Set[ActorRef])
}

trait ConsumerManager extends Actor {
    this: DestinationManager with SendRepliesAs with ActorLogging ⇒
    import context.dispatcher

    protected def connection: Connection

    private var sharedConsumerActors = Map.empty[Destination, ActorRef]
    private var sharedConsumerSubscriptions = Map.empty[Destination, Set[ActorRef]]

    private var dedicatedConsumers = Map.empty[(Destination, ActorRef), ActorRef]

    private val dedicatedNamer = Iterator from 0 map (i ⇒ Helpers.base64(i))

    private def destName(dest: Destination) = {
        val namePre = dest match {
            case _: Queue ⇒ "queue-"
            case _: Topic ⇒ "topic-"
            case _: TempQueue ⇒ "tempqueue-"
            case _: TempTopic ⇒ "temptopic-"
        }

        namePre + dest.name
    }

    private def getSharedConsumer(forDest: Destination): ActorRef = {
        sharedConsumerActors.getOrElse(forDest, {
            val name = "shared-" + destName(forDest)

            val act = context.actorOf(SharedDestinationConsumer.props(connection, forDest, sendRepliesAs), name)
            context watch act
            sharedConsumerActors += (forDest → act)
            act
        })
    }

    private def getDedicatedConsumer(forDest: Destination): ActorRef = {
        dedicatedConsumers.getOrElse(forDest → sender(), {
            val name = destName(forDest) + dedicatedNamer.next()

            val act = context.actorOf(DedicatedDestinationConsumer.props(connection, forDest, sender(), sendRepliesAs), name)
            context watch act
            dedicatedConsumers += ((forDest → sender()) → act)
            act
        })
    }

    protected def handleConsumerMessages: Receive = {
        case Terminated(act) if sharedConsumerActors.values.exists(_ == act) ⇒
            log.debug("{} shut down", act)
            sharedConsumerActors find (_._2 == act) foreach (e ⇒ sharedConsumerActors -= e._1)

        case Terminated(act) if dedicatedConsumers.values.exists(_ == act) ⇒
            log.debug("{} shut down", act)
            dedicatedConsumers find (_._2 == act) foreach (e ⇒ dedicatedConsumers -= e._1)

        case ConsumerTerminated(act) ⇒
            // don't need to stop the actors because they watch subscribers and do it themselves
            sharedConsumerSubscriptions = (Map.empty[Destination, Set[ActorRef]] /: sharedConsumerSubscriptions) {
                case (acc, (dest, subs)) ⇒
                    val newSet = subs - act
                    if (newSet.nonEmpty)
                        acc + (dest → newSet)
                    else
                        acc
            }

            dedicatedConsumers = dedicatedConsumers filterNot (_._1._2 == act)

        case cm: ConsumerMessage if cm.sharedConsumer ⇒
            val newSet = sharedConsumerSubscriptions.getOrElse(cm.destination, Set.empty) + sender()
            sharedConsumerSubscriptions += (cm.destination → newSet)

            getSharedConsumer(cm.destination) forward AddSubscriber

        case cm: ConsumerMessage ⇒
            val consumer = getDedicatedConsumer(cm.destination)
            val subscriber = sender()
            getDestination(cm.destination) onComplete {
                case Failure(t) ⇒ subscriber ! ConsumeFailed(cm.destination, t)
                case Success(jmsDest) ⇒ consumer ! DestAndConsumers(jmsDest, Set.empty)
            }

        case ec@EndConsumption(dest) ⇒
            val key = dest → sender()
            dedicatedConsumers.get(key).fold(sender() ! ConsumptionEnded(dest))(_ forward ec)

        case GetDestAndConsumers(dest) ⇒
            val cons = sharedConsumerSubscriptions.getOrElse(dest, Set.empty)
            getDestination(dest) map (d ⇒ DestAndConsumers(d, cons)) pipeTo sender()
    }
}
