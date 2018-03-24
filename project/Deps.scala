/*
 * Deps.scala
 *
 * Updated: Jan 28, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */

import sbt._

object Deps {
    val activemqBroker = "org.apache.activemq" % "activemq-broker" % Versions.activemq
    val activemqCamel = "org.apache.activemq" % "activemq-camel" % Versions.activemq
    val activemqClient = "org.apache.activemq" % "activemq-client" % Versions.activemq
    val activemqJaas = "org.apache.activemq" % "activemq-jaas" % Versions.activemq
    val akkaActor = "com.typesafe.akka" %% "akka-actor" % Versions.akka
    val akkaSlf = "com.typesafe.akka" %% "akka-slf4j" % Versions.akka
    val akkaTest = "com.typesafe.akka" %% "akka-testkit" % Versions.akka
    val camel = "org.apache.camel" % "camel-core" % Versions.camel
    val jclOverSlf4j = "org.slf4j" % "jcl-over-slf4j" % Versions.slf4j
    val logback = "ch.qos.logback" % "logback-classic" % Versions.logback
    val scalaTest = "org.scalatest" %% "scalatest" % Versions.scalaTest
}
