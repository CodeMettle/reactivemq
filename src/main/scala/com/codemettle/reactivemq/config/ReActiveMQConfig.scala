/*
 * ReActiveMQConfig.scala
 *
 * Updated: Jan 28, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq
package config

import com.typesafe.config.Config
import spray.util.SettingsCompanion

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

/**
 * @author steven
 *
 */
case class ReActiveMQConfig(connFactTimeout: FiniteDuration, reestablishConnections: Boolean,
                            connectionReestablishPeriod: FiniteDuration, producerIdleTimeout: FiniteDuration,
                            consumerIdleTimeout: FiniteDuration, autoConnections: Map[String, String])

object ReActiveMQConfig extends SettingsCompanion[ReActiveMQConfig]("reactivemq") {
    override def fromSubConfig(c: Config): ReActiveMQConfig = {
        ReActiveMQConfig(
            c getFiniteDuration "idle-connection-factory-shutdown",
            c getBoolean        "reestablish-broken-connections",
            c getFiniteDuration "reestablish-attempt-delay",
            c getFiniteDuration "close-unused-producers-after",
            c getFiniteDuration "close-unused-consumers-after",
            (c.getObject("autoconnect").unwrapped().asScala mapValues (_.toString)).toMap
        )
    }
}
