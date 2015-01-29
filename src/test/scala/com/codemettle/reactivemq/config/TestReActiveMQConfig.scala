/*
 * TestReActiveMQConfig.scala
 *
 * Updated: Jan 28, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.config

import org.scalatest._

import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.util.control.Exception.ultimately

/**
 * @author steven
 *
 */
class TestReActiveMQConfig extends FlatSpec with Matchers {
    "a ReActiveMQConfig" should "be instantiable from reference.conf" in {
        val system = ActorSystem("Test")
        ultimately(system.shutdown()) {
            val config = ReActiveMQConfig(system)

            config.connFactTimeout should equal (1.minute)
        }
    }
}
