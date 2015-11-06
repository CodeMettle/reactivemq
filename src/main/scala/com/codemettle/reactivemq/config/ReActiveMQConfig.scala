/*
 * ReActiveMQConfig.scala
 *
 * Updated: Feb 19, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq
package config

import java.{util ⇒ ju}

import com.typesafe.config.{Config, ConfigObject, ConfigValue, ConfigValueType}
import spray.util.SettingsCompanion

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

/**
 * @author steven
 *
 */
case class AutoConnectConfig(address: String, username: Option[String] = None, password: Option[String] = None)

object AutoConnectConfig {
    def parseValue(cv: ConfigValue): AutoConnectConfig = cv.valueType() match {
        case ConfigValueType.STRING ⇒ AutoConnectConfig(cv.unwrapped().asInstanceOf[String])
        case ConfigValueType.OBJECT ⇒
            val map = cv.unwrapped().asInstanceOf[ju.Map[String, String]].asScala
            AutoConnectConfig(map("address"), map.get("username"), map.get("password"))

        case _ ⇒ sys.error(s"$cv is not an OBJECT or STRING")
    }

    def parse(c: ConfigObject): Map[String, AutoConnectConfig] =
        c.entrySet().asScala.map(e ⇒ e.getKey → parseValue(e.getValue)).toMap
}

case class ReActiveMQConfig(connFactTimeout: FiniteDuration, reestablishConnections: Boolean,
                            connectionReestablishPeriod: FiniteDuration, producerIdleTimeout: FiniteDuration,
                            logConsumers: Boolean, queueConsumerTimeout: FiniteDuration,
                            autoConnections: Map[String, AutoConnectConfig], autoconnectTimeout: FiniteDuration)

object ReActiveMQConfig extends SettingsCompanion[ReActiveMQConfig]("reactivemq") {
    override def fromSubConfig(c: Config): ReActiveMQConfig = {
        ReActiveMQConfig(
            c getFiniteDuration "idle-connection-factory-shutdown",
            c getBoolean        "reestablish-broken-connections",
            c getFiniteDuration "reestablish-attempt-delay",
            c getFiniteDuration "close-unused-producers-after",
            c getBoolean        "log-consumers",
            c getFiniteDuration "default-queue-consumer-reply-timeout",
            AutoConnectConfig parse c.getObject("autoconnect"),
            c getFiniteDuration "autoconnect-timeout"
        )
    }
}
