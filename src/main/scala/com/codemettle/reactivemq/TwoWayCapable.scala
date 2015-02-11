/*
 * TwoWayCapable.scala
 *
 * Updated: Feb 11, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

/**
 * @author steven
 *
 */
trait TwoWayCapable {
    protected def oneway: Boolean = false
}

trait Oneway extends TwoWayCapable {
    override final def oneway: Boolean = true
}
