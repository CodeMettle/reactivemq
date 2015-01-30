/*
 * TempQueueReplyManager.scala
 *
 * Updated: Jan 30, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.connection.requestreply

import com.codemettle.reactivemq.ReActiveMQMessages.Consume
import com.codemettle.reactivemq.connection.requestreply.TempQueueReplyManager.RegisterListener
import com.codemettle.reactivemq.model.{AMQMessage, TempQueue}

import akka.actor._
import scala.concurrent.duration._

/**
 * @author steven
 *
 */
object TempQueueReplyManager {
    def props(tempQueue: TempQueue, connection: ActorRef) = {
        Props(new TempQueueReplyManager(tempQueue, connection))
    }

    private[requestreply] case class RegisterListener(correlationId: String, timeout: FiniteDuration)
}

class TempQueueReplyManager(tempQueue: TempQueue, connection: ActorRef) extends Actor with ActorLogging {

    private var outstanding = Map.empty[String, ActorRef]

    override def preStart() = {
        super.preStart()

        connection ! Consume(tempQueue)
    }

    def receive = {
        case msg: AMQMessage ⇒ msg.properties.correlationID match {
            case None ⇒
                log.warning("Received message with no JMSCorrelationID: {}", msg)

            case Some(id) if outstanding contains id ⇒
                log.debug("Got message with JMSCorrelationID {}", id)

                val sendTo = outstanding(id)
                outstanding -= id
                sendTo ! msg

            case Some(id) ⇒
                log.warning("Dropping message with unknown JMSCorrelationID: {}", msg)
        }

        case RegisterListener(corrId, timeout) ⇒
            outstanding += (corrId → sender())
            context watch sender()

        case Terminated(act) ⇒
            outstanding find (_._2 == act) foreach (e ⇒ outstanding -= e._1)
    }
}
