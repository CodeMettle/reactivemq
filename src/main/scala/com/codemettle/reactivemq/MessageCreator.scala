package com.codemettle.reactivemq

import java.{io ⇒ jio}
import javax.jms

/**
  * Created by steven on 1/2/2018.
  */
trait MessageCreator {
  def createTextMessage(text: String): jms.TextMessage

  def createObjectMessage(obj: jio.Serializable): jms.ObjectMessage

  def createBytesMessage(data: ⇒ Array[Byte]): jms.BytesMessage
}

trait DestinationCreator {
  def createQueue(name: String): jms.Queue

  def createTopic(name: String): jms.Topic
}
