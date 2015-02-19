/*
 * ReActiveMQConfig.scala
 *
 * Updated: Feb 19, 2015
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
                            logConsumers: Boolean, queueConsumerTimeout: FiniteDuration,
                            autoConnections: Map[String, String], autoconnectTimeout: FiniteDuration)

object ReActiveMQConfig extends SettingsCompanion[ReActiveMQConfig]("reactivemq") {
    override def fromSubConfig(c: Config): ReActiveMQConfig = {
        ReActiveMQConfig(
            c getFiniteDuration "idle-connection-factory-shutdown",
            c getBoolean        "reestablish-broken-connections",
            c getFiniteDuration "reestablish-attempt-delay",
            c getFiniteDuration "close-unused-producers-after",
            c getBoolean        "log-consumers",
            c getFiniteDuration "default-queue-consumer-reply-timeout",
            (c.getObject("autoconnect").unwrapped().asScala mapValues (_.toString)).toMap,
            c getFiniteDuration "autoconnect-timeout"
        )
    }
}
