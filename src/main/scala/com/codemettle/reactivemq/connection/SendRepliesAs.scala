/*
 * SendRepliesAs.scala
 *
 * Updated: Jan 29, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq.connection

import akka.actor.{Actor, ActorRef}
import akka.pattern.pipe
import scala.concurrent.Future

/**
 * @author steven
 *
 */
private[connection] trait SendRepliesAs {
    this: Actor ⇒
    import context.dispatcher

    protected def sendRepliesAs: ActorRef

    protected def routeFuture[T](to: ActorRef)(f: ⇒ Future[T]) = {
        f.pipeTo(to)(sendRepliesAs)
    }

    protected implicit class SendReply(val u: ActorRef) {
        def tellAs(msg: Any) = u.tell(msg, sendRepliesAs)
    }
}
