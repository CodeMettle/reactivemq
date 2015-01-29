/*
 * ConnectionFactoryActor.scala
 *
 * Updated: Jan 28, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq
package connection

import java.util.UUID
import javax.jms.{Connection, Session}

import org.apache.activemq.ActiveMQConnectionFactory

import com.codemettle.reactivemq.ReActiveMQMessages.{CloseConnection, ConnectionEstablished, ConnectionFailed, ConnectionRequest}
import com.codemettle.reactivemq.connection.ConnectionFactoryActor.{OpenedConnection, WaiterTimedOut, WaitingForConnection, fsm}

import akka.actor._
import akka.pattern.pipe
import scala.concurrent.Future
import scala.util.control.Exception.ignoring
import scala.util.control.NonFatal

/**
 * @author steven
 *
 */
object ConnectionFactoryActor {
    def props(connFact: ActiveMQConnectionFactory) = {
        Props(new ConnectionFactoryActor(connFact))
    }

    private[connection] object fsm {
        sealed trait State
        case object Idle extends State
        case object Connecting extends State
        case object Connected extends State

        case class Data(waitingForConnect: Set[WaitingForConnection] = Set.empty, connection: Option[ActorRef] = None)
    }

    private[connection] case class WaitingForConnection(sender: ActorRef, request: ConnectionRequest,
                                                        reqId: UUID = UUID.randomUUID())
    private case class WaiterTimedOut(reqId: UUID)
    private case class OpenedConnection(conn: Connection, sess: Session)
}

private[connection] class ConnectionFactoryActor(connFact: ActiveMQConnectionFactory) extends FSM[fsm.State, fsm.Data] {
    import context.dispatcher

    startWith(fsm.Idle, fsm.Data())

    private def addConnectWaiter(req: ConnectionRequest) = {
        val waiter = WaitingForConnection(sender(), req)
        setTimer(waiter.reqId.toString, WaiterTimedOut(waiter.reqId), req.timeout)
        goto(fsm.Connecting) using stateData.copy(waitingForConnect = stateData.waitingForConnect + waiter)
    }

    private def cancelTimers() = stateData.waitingForConnect map (_.reqId.toString) foreach cancelTimer

    private def openConnection(): Future[OpenedConnection] = Future {
        val conn = connFact.createConnection()
        conn.start()

        try {
            val sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)

            OpenedConnection(conn, sess)
        } catch {
            case NonFatal(e) ⇒
                ignoring(classOf[Exception])(conn.close())
                throw e
        }
    }

    whenUnhandled {
        case Event(req: ConnectionRequest, _) ⇒ addConnectWaiter(req)

        case Event(WaiterTimedOut(reqId), data) ⇒
            (data.waitingForConnect find (_.reqId == reqId)).fold(stay())(waiter ⇒ {
                waiter.sender ! ConnectionFailed(waiter.request, ConnectionTimedOut)
                stay() using data.copy(waitingForConnect = data.waitingForConnect - waiter)
            })
    }

    when(fsm.Idle)(FSM.NullFunction)

    onTransition {
        case fsm.Idle -> fsm.Connecting ⇒ openConnection() pipeTo self
    }

    when(fsm.Connecting) {
        case Event(Status.Failure(t), data) ⇒
            cancelTimers()

            data.waitingForConnect foreach (w ⇒ w.sender ! ConnectionFailed(w.request, t))

            goto(fsm.Idle) using data.copy(waitingForConnect = Set.empty)

        case Event(OpenedConnection(conn, sess), data) ⇒
            cancelTimers()

            val connAct = context.actorOf(ConnectionActor.props(conn, sess), "conn")
            context watch connAct

            data.waitingForConnect foreach (w ⇒ w.sender ! ConnectionEstablished(w.request, self))

            goto(fsm.Connected) using data.copy(waitingForConnect = Set.empty, connection = Some(connAct))
    }

    when(fsm.Connected) {
        case Event(req: ConnectionRequest, _) ⇒ stay() replying ConnectionEstablished(req, self)

        case Event(CloseConnection, data) ⇒
            data.connection foreach (_ ! CloseConnection)
            stay()

        case Event(Terminated(act), data) if data.connection contains act ⇒
            goto(fsm.Idle) using data.copy(connection = None)
    }

    initialize()
}
