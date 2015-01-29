/*
 * ProducerManager.scala
 *
 * Updated: Jan 29, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.connection

import javax.jms.MessageProducer

import com.codemettle.reactivemq.config.ReActiveMQConfig
import com.codemettle.reactivemq.connection.ProducerManager.{CleanProducer, CreateFailed, ProducerCreated}
import com.codemettle.reactivemq.model.Destination

import akka.actor.{Actor, ActorLogging, Cancellable}
import akka.pattern.pipe
import scala.concurrent.{Future, Promise}
import scala.util.control.Exception.ignoring

/**
 * @author steven
 *
 */
private[connection] object ProducerManager {
    private case class CleanProducer(forDest: Destination)
    private case class ProducerCreated(dest: Destination, prod: MessageProducer)
    private case class CreateFailed(dest: Destination, failure: Throwable)
}

private[connection] trait ProducerManager extends Actor {
    this: DestinationManager with ActorLogging ⇒
    import context.dispatcher

    abstract override def postStop() = {
        super.postStop()

        producers.values foreach (prod ⇒ ignoring(classOf[Exception])(prod.close()))
    }

    private val config = ReActiveMQConfig(spray.util.actorSystem)

    private var prodReqs = Map.empty[Destination, List[Promise[MessageProducer]]]
    private var producers = Map.empty[Destination, MessageProducer]
    private var timers = Map.empty[Destination, Cancellable]

    private def touch(dest: Destination) = {
        timers get dest foreach (_.cancel())
        val newTimer = context.system.scheduler.scheduleOnce(config.producerIdleTimeout, self, CleanProducer(dest))
        timers += (dest → newTimer)
    }

    private def createProducer(dest: Destination) = {
        getDestination(dest) map (jmsDest ⇒ {
            session createProducer jmsDest
        }) map (p ⇒ ProducerCreated(dest, p)) recover {
            case t ⇒ CreateFailed(dest, t)
        } pipeTo self
    }

    protected def getProducer(dest: Destination): Future[MessageProducer] = {
        producers get dest match {
            case Some(prod) ⇒
                touch(dest)
                Future successful prod

            case None ⇒
                val p = Promise[MessageProducer]()
                val newList = p :: prodReqs.getOrElse(dest, {
                    createProducer(dest)
                    Nil
                })

                prodReqs += (dest → newList)
                p.future
        }
    }

    protected def handleProducerMessages: Receive = {
        case ProducerCreated(req, prod) ⇒
            log.debug("Producer created for {}", req)

            producers += (req → prod)

            prodReqs get req foreach (reqs ⇒ reqs foreach (_ success prod))
            prodReqs -= req

            touch(req)

        case CreateFailed(req, t) ⇒
            prodReqs get req foreach (reqs ⇒ reqs foreach (_ failure t))
            prodReqs -= req

        case CleanProducer(dest) ⇒
            log.debug("Closing producer for {}", dest)

            timers get dest foreach (_.cancel())
            timers -= dest

            producers get dest foreach (prod ⇒ ignoring(classOf[Exception])(prod.close()))
            producers -= dest
    }
}
