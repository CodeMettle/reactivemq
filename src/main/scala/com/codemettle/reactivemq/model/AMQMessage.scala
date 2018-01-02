/*
 * AMQMessage.scala
 *
 * Updated: Feb 11, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq
package model

import java.{io ⇒ jio, util ⇒ ju}
import javax.jms

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
 * @author steven
 *
 */
@SerialVersionUID(1L)
case class AMQMessage(body: Any, properties: JMSMessageProperties = JMSMessageProperties(), headers: Map[String, Any] = Map.empty) {
    def jmsMessage(implicit mc: MessageCreator, dc: DestinationCreator): jms.Message = {
        val msg = body match {
            case s: String ⇒ mc createTextMessage s

            case b: Array[Byte] ⇒ mc createBytesMessage b

            case s: jio.Serializable ⇒ mc createObjectMessage s

            case _ ⇒ sys.error(s"$body isn't serializable")
        }

        headers foreach (kv ⇒ msg.setObjectProperty(kv._1, kv._2))

        properties.messageID foreach msg.setJMSMessageID
        msg.setJMSTimestamp(properties.timestamp)
        properties.correlationID foreach msg.setJMSCorrelationID
        properties.replyTo.map(_.jmsDestination) foreach msg.setJMSReplyTo
        properties.destination.map(_.jmsDestination) foreach msg.setJMSDestination
        msg.setJMSDeliveryMode(properties.deliveryMode)
        msg.setJMSRedelivered(properties.redelivered)
        properties.`type` foreach msg.setJMSType
        msg.setJMSExpiration(properties.expiration)
        msg.setJMSPriority(properties.priority)

        msg
    }

    def withCorrelationID(corr: String) = withProperty(_.copy(correlationID = Some(corr)))

    def withType(`type`: String) = withProperty(_.copy(`type` = Some(`type`)))

    def withProperty(f: (JMSMessageProperties) ⇒ JMSMessageProperties) = {
        val newProps = f(properties)
        if (newProps == properties)
            this
        else
            copy(properties = newProps)
    }

    def bodyAs[T : ClassTag]: T = {
        val ct = implicitly[ClassTag[T]]

        ct.runtimeClass.asInstanceOf[Class[T]] cast body
    }

    lazy val camelHeaders: Map[String, Any] = {
        val propHeaders = Map(
            "JMSMessageID" → properties.messageID,
            "JMSTimestamp" → properties.timestamp,
            "JMSCorrelationID" → properties.correlationID,
            "JMSReplyTo" → properties.replyTo,
            "JMSDestination" → (properties.destination map (_.name)),
            "JMSDeliveryMode" → properties.deliveryMode,
            "JMSRedelivered" → properties.redelivered,
            "JMSType" → properties.`type`,
            "JMSExpiration" → properties.expiration,
            "JMSPriority" → properties.priority
        )

        val nonEmptyHeaders = (Map.empty[String, Any] /: propHeaders) {
            case (acc, (_, None)) ⇒ acc
            case (acc, (k, Some(v))) ⇒ acc + (k → v)
            case (acc, (k, v)) ⇒ acc + (k → v)
        }

        headers ++ nonEmptyHeaders
    }
}

object AMQMessage {
    def from(msg: jms.Message) = {

        def readBytes(msg: jms.BytesMessage, bufferSize: Int = 4096): Array[Byte] = {
            if (msg.getBodyLength > Int.MaxValue)
                sys.error(s"Message too large, unable to read ${msg.getBodyLength} bytes of data")

            val buff = new Array[Byte](Math.min(msg.getBodyLength, bufferSize).toInt)

            @tailrec def read(data: Array[Byte]): Array[Byte] = {
                if (msg.getBodyLength == data.length)
                    data
                else {
                    val len = msg.readBytes(buff)
                    val d = buff.take(len)
                    read(data ++ d)
                }
            }
            read(Array.emptyByteArray)
        }

        val body = msg match {
            case tm: jms.TextMessage ⇒ tm.getText
            case om: jms.ObjectMessage ⇒ om.getObject
            case bm: jms.BytesMessage ⇒ readBytes(bm)
            case  m: jms.Message ⇒ null.asInstanceOf[Serializable]
            case _ ⇒ sys.error(s"Don't grok a ${msg.getClass.getSimpleName}")
        }

        val headers = msg.getPropertyNames.asInstanceOf[ju.Enumeration[String]].asScala map
            (pn ⇒ pn → (msg getObjectProperty pn))

        AMQMessage(body, JMSMessageProperties from msg, headers.toMap)
    }
}

case class JMSMessageProperties(messageID: Option[String] = None, timestamp: Long = 0,
                                correlationID: Option[String] = None, replyTo: Option[Destination] = None,
                                destination: Option[Destination] = None,
                                deliveryMode: Int = jms.Message.DEFAULT_DELIVERY_MODE, redelivered: Boolean = false,
                                `type`: Option[String] = None, expiration: Long = 0,
                                priority: Int = jms.Message.DEFAULT_PRIORITY)

object JMSMessageProperties {
    def from(msg: jms.Message) = {
        JMSMessageProperties(Option(msg.getJMSMessageID), msg.getJMSTimestamp, Option(msg.getJMSCorrelationID),
            Option(msg.getJMSReplyTo) map (Destination(_)), Option(msg.getJMSDestination) map (Destination(_)),
            msg.getJMSDeliveryMode, msg.getJMSRedelivered, Option(msg.getJMSType), msg.getJMSExpiration,
            msg.getJMSPriority)
    }
}
