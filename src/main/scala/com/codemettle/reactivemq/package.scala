/*
 * package.scala
 *
 * Updated: Jan 28, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle

import com.typesafe.config.Config

import scala.concurrent.duration._

/**
 * @author steven
 *
 */
package object reactivemq {
    implicit class RichConfig(val u: Config) extends AnyVal {
        def getFiniteDuration(path: String): FiniteDuration =
            u.getDuration(path).toNanos.nanos.toCoarsest match {
                case fd: FiniteDuration => fd
                case _ => sys.error("can't happen")
            }
    }
}
