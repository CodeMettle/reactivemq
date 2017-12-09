package com.codemettle.reactivemq

import java.{io ⇒ jio}
import javax.jms

import org.apache.activemq.command.{ActiveMQBytesMessage, ActiveMQObjectMessage, ActiveMQTextMessage}

/**
  * Created by steven on 1/2/2018.
  */
package object activemq {

  implicit object AMQMessageCreator extends MessageCreator {

    override def createTextMessage(text: String): jms.TextMessage = {
      val msg = new ActiveMQTextMessage
      msg setText text
      msg
    }

    override def createObjectMessage(obj: jio.Serializable): jms.ObjectMessage = {
      val msg = new ActiveMQObjectMessage
      msg setObject obj
      msg
    }

    override def createBytesMessage(data: ⇒ Array[Byte]): jms.BytesMessage = {
      val msg = new ActiveMQBytesMessage
      msg writeBytes data
      msg
    }

  }

}
