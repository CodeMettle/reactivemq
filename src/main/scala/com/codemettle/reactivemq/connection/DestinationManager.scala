/*
 * DestinationManager.scala
 *
 * Updated: Feb 6, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.connection

import javax.jms

import com.codemettle.reactivemq.activemq.ConnectionFactory.Connection
import com.codemettle.reactivemq.connection.DestinationManager.{CreateFailure, DestinationCreated}
import com.codemettle.reactivemq.model._

import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe
import scala.concurrent.{Future, Promise}

/**
 * @author steven
 *
 */
private[connection] object DestinationManager {
    private case class DestinationCreated(req: Destination, dest: jms.Destination)
    private case class CreateFailure(dest: Destination, failure: Throwable)
}

private[connection] trait DestinationManager extends Actor {
    this: ActorLogging =>
    import context.dispatcher

    protected def connection: Connection

    private var destRequests = Map.empty[Destination, List[Promise[jms.Destination]]]
    private var destinations = Map.empty[Destination, jms.Destination]

    private def createDestination(dest: Destination) = {
        (Future {
            dest match {
                case tt: TempTopic => tt.jmsDest
                case tq: TempQueue => tq.jmsDest
                case Topic(name) => connection createTopic name
                case Queue(name) => connection createQueue name
            }
        } map (d => DestinationCreated(dest, d)) recover {
            case t => CreateFailure(dest, t)
        }) pipeTo self
    }

    protected def getDestination(dest: Destination): Future[jms.Destination] = {
        destinations get dest match {
            case Some(d) => Future successful d
            case None =>
                val p = Promise[jms.Destination]()
                val newList = p :: destRequests.getOrElse(dest, {
                    createDestination(dest)
                    Nil
                })
                destRequests += (dest -> newList)
                p.future
        }
    }

    protected def handleDestinationMessages: Receive = {
        case DestinationCreated(req, dest) =>
            destinations += (req -> dest)

            destRequests get req foreach (reqs => reqs foreach (_ success dest))
            destRequests -= req

        case CreateFailure(req, t) =>
            destRequests get req foreach (reqs => reqs foreach (_ failure t))
            destRequests -= req
    }
}
