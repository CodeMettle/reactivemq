/*
 * AMQMessage.scala
 *
 * Updated: Jan 29, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.model

import java.{io => jio, util => ju}
import javax.jms.{Message, ObjectMessage, TextMessage}

import org.apache.activemq.command.{ActiveMQObjectMessage, ActiveMQTextMessage}

import scala.collection.JavaConverters._

/**
 * @author steven
 *
 */
@SerialVersionUID(1L)
case class AMQMessage(body: Any, headers: Map[String, Any] = Map.empty) {
    def jmsMessage = {
        val msg = body match {
            case s: String ⇒
                val msg = new ActiveMQTextMessage
                msg setText s
                msg

            case s: jio.Serializable ⇒
                val msg = new ActiveMQObjectMessage
                msg setObject s
                msg

            case _ ⇒ sys.error(s"$body isn't serializable")
        }

        headers foreach (kv ⇒ msg.setObjectProperty(kv._1, kv._2))

        msg
    }
}

object AMQMessage {
    def from(msg: Message) = {
        val body = msg match {
            case tm: TextMessage ⇒ tm.getText
            case om: ObjectMessage ⇒ om.getObject
            case _ ⇒ sys.error(s"Don't grok a ${msg.getClass.getSimpleName}")
        }

        val headers = msg.getPropertyNames.asInstanceOf[ju.Enumeration[String]].asScala map
            (pn ⇒ pn → (msg getObjectProperty pn))

        AMQMessage(body, headers.toMap)
    }
}
