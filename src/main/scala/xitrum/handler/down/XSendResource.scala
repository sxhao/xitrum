package xitrum.handler.down

import java.io.{File, RandomAccessFile}
import io.netty.channel.{ChannelHandler, ChannelHandlerContext, ChannelFuture, DefaultFileRegion, ChannelFutureListener, ChannelOutboundHandlerAdapter, ChannelPromise}
import io.netty.handler.codec.http.{HttpHeaders, HttpMethod, HttpRequest, FullHttpResponse, HttpResponseStatus, HttpVersion}
import ChannelHandler.Sharable
import HttpHeaders.Names._
import HttpHeaders.Values._
import HttpMethod._
import HttpResponseStatus._
import HttpVersion._
import xitrum.{Config, Log}
import xitrum.etag.{Etag, NotModified}
import xitrum.handler.{AccessLog, HandlerEnv}
import xitrum.handler.up.NoPipelining
import xitrum.util.{Gzip, Mime}
import io.netty.buffer.Unpooled

object XSendResource extends Log {
  // setClientCacheAggressively should be called at PublicResourceServer, not
  // here because XSendResource may be used by applications which does not want
  // to clients to cache.

  val CHUNK_SIZE            = 8 * 1024
  val X_SENDRESOURCE_HEADER = "X-Sendresource"

  // See comment of X_SENDFILE_HEADER_IS_FROM_CONTROLLER
  val X_SENDRESOURCE_HEADER_IS_FROM_CONTROLLER = "X-Sendresource-Is-From-Controller"

  def setHeader(response: FullHttpResponse, path: String, fromController: Boolean) {
    HttpHeaders.setHeader(response, X_SENDRESOURCE_HEADER, path)
    if (fromController) HttpHeaders.setHeader(response, X_SENDRESOURCE_HEADER_IS_FROM_CONTROLLER, "true")
  }

  def isHeaderSet(response: FullHttpResponse) = response.headers.contains(X_SENDRESOURCE_HEADER)

  /** @return false if not found */
  def sendResource(ctx: ChannelHandlerContext, msg: Object, promise: ChannelPromise, request: HttpRequest, response: FullHttpResponse, path: String, noLog: Boolean) {
    Etag.forResource(path, Gzip.isAccepted(request)) match {
      case Etag.NotFound =>
        // Keep alive is handled by XSendFile
        XSendFile.set404Page(response, noLog)

      case Etag.Small(bytes, etag, mimeo, gzipped) =>
        if (Etag.areEtagsIdentical(request, etag)) {
          response.setStatus(NOT_MODIFIED)
          HttpHeaders.setContentLength(response, 0)
          response.content.clear()
        } else {
          Etag.set(response, etag)
          if (mimeo.isDefined) HttpHeaders.setHeader(response, CONTENT_TYPE, mimeo.get)
          if (gzipped)         HttpHeaders.setHeader(response, CONTENT_ENCODING, "gzip")

          HttpHeaders.setContentLength(response, bytes.length)
          if ((request.getMethod == HEAD || request.getMethod == OPTIONS) && response.getStatus == OK) {
            // http://stackoverflow.com/questions/3854842/content-length-header-with-head-requests
            response.content.clear()
          } else {
            response.content.clear()
            response.content.writeBytes(Unpooled.wrappedBuffer(bytes))
          }
        }

        val channel = ctx.channel
        NoPipelining.setResponseHeaderAndResumeReadingForKeepAliveRequestOrCloseOnComplete(request, response, channel, promise)

        if (!noLog) {
          val remoteAddress = channel.remoteAddress
          AccessLog.logResourceInJarAccess(remoteAddress, request, response)
        }
    }
    ctx.write(msg, promise)
  }
}

/**
 * This handler sends resource files (should be small) in classpath.
 */
@Sharable
class XSendResource extends ChannelOutboundHandlerAdapter {
  import XSendResource._

  override def write(ctx: ChannelHandlerContext, msg: Object, promise: ChannelPromise) {
    if (!msg.isInstanceOf[HandlerEnv]) {
      ctx.write(msg, promise)
      return
    }

    val env      = msg.asInstanceOf[HandlerEnv]
    val request  = env.request
    val response = env.response
    val path     = HttpHeaders.getHeader(response, X_SENDRESOURCE_HEADER)
    if (path == null) {
      ctx.write(msg, promise)
      return
    }

    // Remove non-standard header to avoid leaking information
    HttpHeaders.removeHeader(response, X_SENDRESOURCE_HEADER)

    // See comment of X_SENDRESOURCE_HEADER_IS_FROM_CONTROLLER
    // Remove non-standard header to avoid leaking information
    val noLog = response.headers.contains(X_SENDRESOURCE_HEADER_IS_FROM_CONTROLLER)
    if (noLog) HttpHeaders.removeHeader(response, X_SENDRESOURCE_HEADER_IS_FROM_CONTROLLER)

    sendResource(ctx, msg, promise, request, response, path, noLog)
  }
}
