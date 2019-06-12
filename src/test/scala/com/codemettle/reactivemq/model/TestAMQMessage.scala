/*
 * TestAMQMessage.scala
 *
 * Updated: Feb 9, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.model

import java.{util => ju}
import javax.jms

import org.apache.activemq.command.ActiveMQBytesMessage
import org.scalatest._

import com.codemettle.reactivemq.DestinationCreator

import scala.util.Random

/**
 * @author steven
 *
 */
class TestAMQMessage extends FlatSpec with Matchers {
    "An AMQMessage" should "have a working bodyAs method" in {
        val msg1 = AMQMessage(Topic("test"))

        msg1.bodyAs[Topic] should equal (Topic("test"))

        val msg2 = AMQMessage("ueo")

        val strBody: String = msg2.bodyAs[String]

        val nullmsg = AMQMessage(null)

        val strBody2: String = nullmsg.bodyAs[String]
        val topicBody: Topic = nullmsg.bodyAs[Topic]

        val intBody: java.lang.Integer = AMQMessage(4).bodyAs[java.lang.Integer]
    }

    it should "throw CCE on invalid bodyAs" in {
        val msg = AMQMessage(Topic("test"))

        a[ClassCastException] shouldBe thrownBy (msg.bodyAs[String])
    }

    "A bytes message" should "read the data into a byte[]" in {
        val data = {
            val d = new Array[Byte](32)
            Random.nextBytes(d)
            d
        }
        val msg = AMQMessage(data)

        msg.bodyAs[Array[Byte]] should equal(data)
    }

    it should "convert to a JMS message" in {
        import com.codemettle.reactivemq.activemq._

        implicit val dc: DestinationCreator = new DestinationCreator {
            override def createTopic(name: String): jms.Topic = null

            override def createQueue(name: String): jms.Queue = null
        }

        val msg = AMQMessage(Array.fill[Byte](100)(0))

        val converted = msg.jmsMessage

        converted shouldBe a[jms.BytesMessage]

        converted.asInstanceOf[jms.BytesMessage].reset()

        converted.asInstanceOf[jms.BytesMessage].getBodyLength should equal (100)
    }

    it should "fail for messages that are too large" in {
        val msgLen: Long = Int.MaxValue.toLong + 10
        val msg = new ActiveMQBytesMessage() {
            override def getBodyLength: Long = msgLen

            override def getPropertyNames: ju.Enumeration[_] = super.getPropertyNames
        }
        the[RuntimeException] thrownBy
            AMQMessage.from(msg) should have message s"Message too large, unable to read $msgLen bytes of data"
    }

    it should "work with buffered data that is presented in chunks" in {
        val data: Array[Byte] = {
            val arr = new Array[Byte](2048)
            Random.nextBytes(arr)
            arr
        }
        val amqMessage = new ActiveMQBytesMessage() {
            lazy val queue = {
                val q = scala.collection.mutable.Queue[Array[Byte]]()
                data.grouped(64).foreach(q.enqueue(_))
                q
            }
            override val getBodyLength: Long = queue.map(_.length).sum

            override def readBytes(array: Array[Byte]): Int = {
                val d = queue.dequeue
                d.zipWithIndex.foreach { case (b, idx) => array.update(idx, b) }
                d.length
            }

            override def getPropertyNames: ju.Enumeration[_] = super.getPropertyNames
        }

        val msg = AMQMessage.from(amqMessage)
        msg.bodyAs[Array[Byte]].length should equal(data.length)
        msg.bodyAs[Array[Byte]] should equal(data)
    }
}
