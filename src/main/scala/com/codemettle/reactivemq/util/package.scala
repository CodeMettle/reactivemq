package com.codemettle.reactivemq

import akka.actor.{ActorContext, ActorRefFactory, ActorSystem}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.implicitConversions

/**
  * Created by steven on 9/19/2017.
  */
package object util {
  implicit def actorSystem(implicit arf: ActorRefFactory): ActorSystem = arf match {
    case as: ActorSystem => as
    case ac: ActorContext => ac.system
    case _ => sys.error(s"Unsupported ActorRefFactory $arf")
  }

  implicit class RichFuture[T](val u: Future[T]) extends AnyVal {
    def await(implicit timeout: Duration = 1.minute): T =
      Await.result(u, timeout)

    def ready(implicit timeout: Duration = 1.minute): Future[T] =
      Await.ready(u, timeout)
  }
}
