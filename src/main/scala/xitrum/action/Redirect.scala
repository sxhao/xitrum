package xitrum.action

import java.lang.reflect.Method

import org.jboss.netty.channel.ChannelFuture

import org.jboss.netty.handler.codec.http.{ HttpHeaders, HttpResponseStatus }
import HttpHeaders.Names.LOCATION
import HttpResponseStatus.FOUND

import xitrum.ActionEnv
import xitrum.handler.up.Dispatcher
import xitrum.routing.Routes

trait Redirect {
  this: ActionEnv =>

  /** See also forwardTo. */
  def redirectTo(location: String, status: HttpResponseStatus = FOUND): ChannelFuture = {
    response.setStatus(status)
    HttpHeaders.setContentLength(response, 0)
    response.setHeader(LOCATION, location)
    respond()
  }

  /** See also forwardTo. */
  def redirectTo(actionClass: Class[_ <: ActionEnv], params: (String, Any)*): ChannelFuture = {
    redirectTo(Routes.routes.reverseMappings(actionClass).url(params: _*))
  }

  //----------------------------------------------------------------------------

  /** Set to true by forwardTo. */
  var forwarding = false

  /**
   * Tells another action to process the current request for the current action.
   * See also redirectTo.
   */
  def forwardTo(actionClass: Class[_ <: ActionEnv]) {
    forwarding = true
    Dispatcher.dispatchWithFailsafe(actionClass, 0, handlerEnv)
  }
}
