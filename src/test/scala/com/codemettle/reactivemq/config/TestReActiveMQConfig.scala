/*
 * TestReActiveMQConfig.scala
 *
 * Updated: Jan 28, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.config

import com.typesafe.config.ConfigFactory
import org.scalatest._

import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.util.control.Exception.ultimately

/**
 * @author steven
 *
 */
class TestReActiveMQConfig extends FlatSpec with Matchers with OptionValues {
    private def confTestCfg(cfg: String)(f: (ReActiveMQConfig) ⇒ Unit) = {
        val system = ActorSystem("Test", ConfigFactory parseString cfg withFallback ConfigFactory.load())
        ultimately(system.shutdown()) {
            f(ReActiveMQConfig(system))
        }
    }
    private def confTest(f: (ReActiveMQConfig) ⇒ Unit) = confTestCfg("")(f)

    "a ReActiveMQConfig" should "be instantiable from reference.conf" in {
        confTest { config ⇒
            config.connFactTimeout should equal (1.minute)
        }
    }

    it should "be instantiable from config with old autoconnects" in {
        val oldConfig =
            """reactivemq {
              |  autoconnect {
              |    server1 = "address.to.s1"
              |    server2 = "address.to.s2"
              |  }
              |}
            """.stripMargin

        confTestCfg(oldConfig) { config ⇒
            config.autoConnections should contain key "server1"
            config.autoConnections should contain key "server2"

            config.autoConnections("server1").address should be ("address.to.s1")
            config.autoConnections("server2").address should be ("address.to.s2")

            config.autoConnections("server1").username should be ('empty)
            config.autoConnections("server2").username should be ('empty)
            config.autoConnections("server1").password should be ('empty)
            config.autoConnections("server2").password should be ('empty)
        }
    }

    it should "be instantiable from config with new autoconnects" in {
        val newConfig =
            """reactivemq {
              |  autoconnect {
              |    server1.address = "address.to.s1"
              |    server2 {
              |      address = "address.to.s2"
              |      username = user
              |      password = pass
              |    }
              |  }
              |}
            """.stripMargin

        confTestCfg(newConfig) { config ⇒
            config.autoConnections should contain key "server1"
            config.autoConnections should contain key "server2"

            config.autoConnections("server1").address should be ("address.to.s1")
            config.autoConnections("server2").address should be ("address.to.s2")

            config.autoConnections("server1").username should be ('empty)
            config.autoConnections("server2").username.value should be ("user")
            config.autoConnections("server1").password should be ('empty)
            config.autoConnections("server2").password.value should be ("pass")
        }
    }
}
