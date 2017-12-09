/*
 * Destination.scala
 *
 * Updated: Feb 6, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.model

import javax.jms

import com.codemettle.reactivemq.DestinationCreator
import com.codemettle.reactivemq.activemq.ConnectionFactory.Connection

/**
 * @author steven
 *
 */
sealed trait Destination {
    def name: String
    def jmsDestination(implicit dc: DestinationCreator): jms.Destination
}

@SerialVersionUID(1L)
case class Queue(name: String) extends Destination {
    override def jmsDestination(implicit dc: DestinationCreator): jms.Destination =
        implicitly[DestinationCreator] createQueue name
}

@SerialVersionUID(1L)
case class Topic(name: String) extends Destination {
    override def jmsDestination(implicit dc: DestinationCreator): jms.Destination =
        implicitly[DestinationCreator] createTopic name
}

@SerialVersionUID(1L)
case class TempQueue(jmsDest: jms.TemporaryQueue) extends Destination {

    override def name: String = jmsDest.getQueueName

    override def jmsDestination(implicit dc: DestinationCreator): jms.Destination = jmsDest
}

object TempQueue {
    def create(connection: Connection) = connection.createTemporaryQueue
}

@SerialVersionUID(1L)
case class TempTopic(jmsDest: jms.TemporaryTopic) extends Destination {

    override def name: String = jmsDest.getTopicName

    override def jmsDestination(implicit dc: DestinationCreator): jms.Destination = jmsDest
}

object TempTopic {
    def create(connection: Connection) = connection.createTemporaryTopic
}

object Destination {
    def apply(jmsDest: jms.Destination) = jmsDest match {
        case tq: jms.TemporaryQueue ⇒ TempQueue(tq)
        case tt: jms.TemporaryTopic ⇒ TempTopic(tt)
        case q: jms.Queue ⇒ Queue(q.getQueueName)
        case t: jms.Topic ⇒ Topic(t.getTopicName)
    }
}
