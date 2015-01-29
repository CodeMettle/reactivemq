/*
 * ConnectionFactoryActor.scala
 *
 * Updated: Jan 29, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq
package connection

import java.util.UUID
import javax.jms.{JMSException, ExceptionListener, Connection, Session}

import org.apache.activemq.ActiveMQConnectionFactory

import com.codemettle.reactivemq.ReActiveMQMessages._

import com.codemettle.reactivemq.config.ReActiveMQConfig
import com.codemettle.reactivemq.connection.ConnectionFactoryActor._

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
        case object Closing extends State
        case object Reconnecting extends State

        case class Data(waitingForConnect: Set[ConnectionWaiter] = Set.empty, connection: Option[ActorRef] = None,
                        reconnectInFlight: Boolean = false, subscriptions: Subscriptions = Subscriptions()) {
            def isAListener(act: ActorRef) = subscriptions isAListener act

            def withConnectionListener(act: ActorRef) = copy(subscriptions = subscriptions withConnectionListener act)

            def withoutListener(act: ActorRef) = copy(subscriptions = subscriptions withoutListener act)
        }
    }

    private[connection] case class Subscriptions(connectionListeners: Set[ActorRef] = Set.empty) {
        def withConnectionListener(act: ActorRef) = {
            if (connectionListeners(act)) this else copy(connectionListeners = connectionListeners + act)
        }

        def isAListener(act: ActorRef) = {
            connectionListeners(act)
        }

        private def removeListener(act: ActorRef) = {
            copy(connectionListeners = connectionListeners - act)
        }

        def withoutListener(act: ActorRef) = {
            if (isAListener(act)) removeListener(act) else this
        }
    }

    private[connection] sealed trait ConnectionWaiter {
        def reqId: UUID
        def handleFailure(t: Throwable)(implicit connFact: ActorRef)
        def handleSuccess(connection: ActorRef)(implicit connFact: ActorRef)
    }

    private[connection] case class WaitingForConnection(sender: ActorRef, request: ConnectionRequest,
                                                        reqId: UUID = UUID.randomUUID()) extends ConnectionWaiter {
        override def handleFailure(t: Throwable)(implicit connFact: ActorRef): Unit = {
            sender ! ConnectionFailed(request, t)
        }

        override def handleSuccess(connection: ActorRef)(implicit connFact: ActorRef): Unit = {
            sender ! ConnectionEstablished(request, connFact)
        }
    }

    private[connection] case class WaitingForOperation(sender: ActorRef, op: ConnectedOperation,
                                                       reqId: UUID = UUID.randomUUID()) extends ConnectionWaiter {
        override def handleFailure(t: Throwable)(implicit connFact: ActorRef): Unit = {
            sender ! Status.Failure(t)
        }

        override def handleSuccess(connection: ActorRef)(implicit connFact: ActorRef): Unit = {
            connection.tell(op, sender)
        }
    }

    private case class WaiterTimedOut(reqId: UUID)
    private case class OpenedConnection(conn: Connection, sess: Session)
    private case object ReestablishConnection

    private[connection] case class ConnectionException(e: JMSException)

    private class Listener(act: ActorRef) extends ExceptionListener {
        override def onException(exception: JMSException): Unit = act ! ConnectionException(exception)
    }
}

private[connection] class ConnectionFactoryActor(connFact: ActiveMQConnectionFactory) extends FSM[fsm.State, fsm.Data] with Stash {
    import context.dispatcher

    startWith(fsm.Idle, fsm.Data())

    private val config = ReActiveMQConfig(spray.util.actorSystem)

    private def getConnectWaiter(req: ConnectionRequest) = {
        val waiter = WaitingForConnection(sender(), req)
        setTimer(waiter.reqId.toString, WaiterTimedOut(waiter.reqId), req.timeout)
        waiter
    }

    private def getOperationWaiter(op: ConnectedOperation) = {
        val waiter = WaitingForOperation(sender(), op)
        setTimer(waiter.reqId.toString, WaiterTimedOut(waiter.reqId), op.timeout)
        waiter
    }

    private def cancelWaitConnectTimers() = stateData.waitingForConnect map (_.reqId.toString) foreach cancelTimer

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

    private def connectionFailedState(data: fsm.Data, failure: Throwable) = {
        cancelWaitConnectTimers()

        data.waitingForConnect foreach (w ⇒ w handleFailure failure)

        data.copy(waitingForConnect = Set.empty)
    }

    private def connectionEstablishedState(conn: Connection, sess: Session, data: fsm.Data) = {
        cancelWaitConnectTimers()

        val connAct = context.actorOf(ConnectionActor.props(conn, sess, self), "conn")
        conn setExceptionListener new Listener(connAct)
        context watch connAct

        data.waitingForConnect foreach (w ⇒ w handleSuccess connAct)

        data.copy(waitingForConnect = Set.empty, connection = Some(connAct))
    }

    whenUnhandled {
        case Event(CloseConnection, _) ⇒ stay() // only handle if we're actually open

        case Event(req: ConnectionRequest, data) ⇒
            // only need to explicitly handle if we're in idle (start connecting) or connected (immediately respond)
            stay() using data.copy(waitingForConnect = data.waitingForConnect + getConnectWaiter(req))

        case Event(op: ConnectedOperation, data) ⇒
            // only need to explicitly handle if we're in idle (start connecting) or connected (immediately respond)
            stay() using data.copy(waitingForConnect = data.waitingForConnect + getOperationWaiter(op))

        case Event(WaiterTimedOut(reqId), data) ⇒
            (data.waitingForConnect find (_.reqId == reqId)).fold(stay())(waiter ⇒ {
                waiter handleFailure ConnectionTimedOut
                stay() using data.copy(waitingForConnect = data.waitingForConnect - waiter)
            })

        case Event(SubscribeToConnections, data) ⇒
            context watch sender()

            sender() ! {
                if (stateData.connection.isDefined)
                    ConnectionReestablished(self)
                else
                    ConnectionInterrupted(self)
            }

            stay() using (data withConnectionListener sender())

        case Event(Terminated(act), data) if data isAListener act ⇒
            stay() using (data withoutListener act)
   }

    when(fsm.Idle, config.connFactTimeout) {
        case Event(req: ConnectionRequest, data) ⇒
            goto(fsm.Connecting) using data.copy(waitingForConnect = data.waitingForConnect + getConnectWaiter(req))

        case Event(op: ConnectedOperation, data) ⇒
            goto(fsm.Connecting) using data.copy(waitingForConnect = data.waitingForConnect + getOperationWaiter(op))

        case Event(StateTimeout, _) ⇒
            log.debug("Idle for {}, shutting down", config.connFactTimeout)
            stop()
    }

    onTransition {
        case _ -> fsm.Connecting ⇒ openConnection() pipeTo self
    }

    when(fsm.Connecting) {
        case Event(CloseConnection, _) ⇒ // unstash when leaving this state and deal with it then
            stash()
            stay()

        case Event(Status.Failure(t), data) ⇒
            goto(fsm.Idle) using connectionFailedState(data, t)

        case Event(OpenedConnection(conn, sess), data) ⇒
            goto(fsm.Connected) using connectionEstablishedState(conn, sess, data)
    }

    onTransition {
        case fsm.Connecting -> _ ⇒ unstashAll()
    }

    when(fsm.Connected) {
        case Event(req: ConnectionRequest, _) ⇒ stay() replying ConnectionEstablished(req, self)

        case Event(op: ConnectedOperation, data) ⇒
            data.connection foreach (_ forward op)
            stay()

        case Event(CloseConnection, data) ⇒ goto(fsm.Closing)

        case Event(Terminated(act), data) if data.connection contains act ⇒
            // an unexpected termination, since CloseConnection requests take us to the Closing state
            val interruptMsg = ConnectionInterrupted(self)
            data.subscriptions.connectionListeners foreach (_ ! interruptMsg)

            if (config.reestablishConnections)
                goto(fsm.Reconnecting) using data.copy(connection = None)
            else {
                log debug "Not attempting to reestablish connection"
                goto(fsm.Idle) using data.copy(connection = None)
            }
    }

    onTransition {
        case fsm.Connected -> fsm.Closing ⇒ stateData.connection foreach (_ ! CloseConnection)
        case fsm.Connected -> fsm.Reconnecting ⇒ setTimer("reestablish", ReestablishConnection, config.connectionReestablishPeriod)
    }

    when(fsm.Closing) {
        case Event(Terminated(act), data) if data.connection contains act ⇒
            if (data.waitingForConnect.nonEmpty) // connect requests came in while disconnected
                goto(fsm.Connecting) using data.copy(connection = None)
            else
                goto(fsm.Idle) using data.copy(connection = None)
    }

    when(fsm.Reconnecting) {
        case Event(CloseConnection, data) ⇒
            if (data.reconnectInFlight) {
                stash() // deal with it when the connect works or fails

                stay()
            } else {
                cancelTimer("reestablish")

                goto(fsm.Idle) using connectionFailedState(data, ConnectionClosedWhileReconnecting)
            }

        case Event(ReestablishConnection, data) ⇒
            log debug "Attempting to reestablish connection..."
            openConnection() pipeTo self
            stay() using data.copy(reconnectInFlight = true)

        case Event(Status.Failure(t), data) ⇒
            // unstash any CloseConnections we got
            unstashAll()

            // any CloseConnections will cancel this
            setTimer("reestablish", ReestablishConnection, config.connectionReestablishPeriod)

            stay() using connectionFailedState(data, t).copy(reconnectInFlight = false)

        case Event(OpenedConnection(conn, sess), data) ⇒
            val reconnMsg = ConnectionReestablished(self)
            data.subscriptions.connectionListeners foreach (_ ! reconnMsg)

            unstashAll()

            goto(fsm.Connected) using connectionEstablishedState(conn, sess, data).copy(reconnectInFlight = false)
    }

    initialize()
}
