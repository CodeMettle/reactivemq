package com.codemettle.reactivemq.util

import com.typesafe.config.Config

import akka.actor.{ActorRefFactory, ActorSystem}

/**
  * Created by steven on 9/19/2017.
  *
  * I've looked at spray and akka source before, so this isn't clean room or anything
  */
abstract class SettingsCompanion[T](path: String) {
  private var cache = Map.empty[ActorSystem, T]

  private def materialize(system: ActorSystem): T =
    cache.getOrElse(system, {
      val t = fromSubConfig(system.settings.config.getConfig(path))
      cache += (system → t)
      t
    })

  def apply(arf: ActorRefFactory): T = materialize(actorSystem(arf))

  def fromSubConfig(c: Config): T
}
