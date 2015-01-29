/*
 * Destination.scala
 *
 * Updated: Jan 29, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.model

/**
 * @author steven
 *
 */
sealed trait Destination {
    def name: String
}

@SerialVersionUID(1L)
case class Queue(name: String) extends Destination

@SerialVersionUID(1L)
case class Topic(name: String) extends Destination
