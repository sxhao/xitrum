package xitrum

import akka.actor.{Actor, ActorRef}

import xitrum.handler.{DefaultHttpChannelInitializer, HandlerEnv}
import xitrum.sockjs.{CloseFromHandler, MessageFromHandler}
import xitrum.util.SeriDeseri

//------------------------------------------------------------------------------

case class SockJsText(text: String)

/**
 * An actor will be created when there's new SockJS session. It will be stopped when
 * the session is closed.
 */
trait SockJsAction extends Actor with Action {
  // Ref of xitrum.sockjs.{NonWebSocketSessionActor, WebSocket, or RawWebSocket}
  private[this] var sessionActorRef: ActorRef = _

  def receive = {
    case (sessionActorRef: ActorRef, action: Action) =>
      this.sessionActorRef = sessionActorRef

      apply(action.handlerEnv)
      execute()
  }

  /**
   * The current action is the one just before switching to this SockJS actor.
   * You can extract session data, request headers etc. from it, but do not use
   * respondText, respondView etc. Use respondSockJsText and respondSockJsClose.
   */
  def execute()

  //----------------------------------------------------------------------------

  def respondSockJsText(text: String) {
    sessionActorRef ! MessageFromHandler(text)
  }

  def respondSockJsJson(scalaObject: AnyRef) {
    val json = SeriDeseri.toJson(scalaObject)
    sessionActorRef ! MessageFromHandler(json)
  }

  def respondSockJsClose() {
    // sessionActorRef will stop this actor when it stops.
    //
    // For non-WebSocket session, until the timeout occurs, the server must serve
    // the close message.
    sessionActorRef ! CloseFromHandler
  }
}
