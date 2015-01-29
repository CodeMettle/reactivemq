/*
 * package.scala
 *
 * Updated: Jan 28, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle

import com.typesafe.config.{Config, ConfigException}

import scala.concurrent.duration.FiniteDuration

/**
 * @author steven
 *
 */
package object reactivemq {
    implicit class RichConfig(val u: Config) extends AnyVal {
        def getFiniteDuration(path: String): FiniteDuration = {
            import spray.util.pimpConfig

            u.getDuration(path) match {
                case fd: FiniteDuration ⇒ fd
                case _ ⇒ throw new ConfigException.BadValue(path, "Not a FiniteDuration")
            }
        }
    }

    implicit class OldOption[T](val u: Option[T]) extends AnyVal {
        def contains(item: T) = u exists (_ == item)
    }
}
