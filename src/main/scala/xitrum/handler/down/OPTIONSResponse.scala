package xitrum.handler.down

import io.netty.buffer.Unpooled
import io.netty.channel.{ChannelHandler, ChannelHandlerContext, ChannelOutboundHandlerAdapter, ChannelPromise}
import io.netty.handler.codec.http.{DefaultHttpResponse, HttpHeaders, HttpMethod, HttpRequest, HttpResponse, HttpResponseStatus}
import ChannelHandler.Sharable
import HttpHeaders.Names._
import HttpMethod._
import HttpResponseStatus._

import xitrum.Config
import xitrum.etag.NotModified
import xitrum.handler.{AccessLog, HandlerEnv}

@Sharable
class OPTIONSResponse extends ChannelOutboundHandlerAdapter {
  override def write(ctx: ChannelHandlerContext, msg: Object, promise: ChannelPromise) {
    if (!msg.isInstanceOf[HandlerEnv]) {
      ctx.write(msg, promise)
      return
    }

    val env      = msg.asInstanceOf[HandlerEnv]
    val request  = env.request
    val response = env.response
    val pathInfo = env.pathInfo

    if (request.getMethod == OPTIONS) {
      AccessLog.logOPTIONS(request)

      if (pathInfo == null) {
        // Static files/resources
        if (response.getStatus != NOT_FOUND) response.setStatus(NO_CONTENT)
      } else {
        // Dynamic resources
        if (!Config.routes.tryAllMethods(pathInfo).isEmpty)
          response.setStatus(NO_CONTENT)
        else
          response.setStatus(NOT_FOUND)
      }

      HttpHeaders.setContentLength(response, 0)
      NotModified.setClientCacheAggressively(response)
      response.content.clear()
    }

    ctx.write(msg, promise)
  }
}
