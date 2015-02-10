/*
 * TestAMQMessage.scala
 *
 * Updated: Feb 9, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.model

import org.scalatest._

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
}
