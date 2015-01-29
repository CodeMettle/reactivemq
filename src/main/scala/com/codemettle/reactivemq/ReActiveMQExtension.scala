/*
 * ReActiveMQExtension.scala
 *
 * Updated: Jan 28, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

import akka.actor._

/**
 * @author steven
 *
 */
class ReActiveMQExtensionImpl(implicit arf: ActorRefFactory) extends Extension {
    val manager = arf.actorOf(Manager.props, "reActiveMQ")
}

object ReActiveMQExtension extends ExtensionId[ReActiveMQExtensionImpl] with ExtensionIdProvider {
    override def createExtension(system: ExtendedActorSystem) = new ReActiveMQExtensionImpl()(system)

    override def lookup() = ReActiveMQExtension
}
