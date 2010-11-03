package xt.middleware

import xt._
import xt.framework.Controller

import java.lang.reflect.Method

import org.jboss.netty.channel.Channel
import org.jboss.netty.handler.codec.http.{HttpRequest, HttpResponse, HttpMethod, HttpResponseStatus}

/**
 * This middleware should be put behind ParamsParser and MultipartParamsParser.
 */
object Dispatcher extends Logger {
  type Route           = (HttpMethod, String, String)
  type CompiledPattern = Array[(String, Boolean)]  // String: token, Boolean: true if the token is constant
  type Csas            = (String, String)
  type KA              = (Class[Controller], Method)
  type CompiledCsas    = (Csas, KA)
  type CompiledRoute   = (HttpMethod, CompiledPattern, Csas, KA)
  type UriParams       = java.util.LinkedHashMap[String, java.util.List[String]]

  private var compiledRoutes: Iterable[CompiledRoute] = _

  var viewPaths: List[String] = _

  /**
   * Application that does not use view (Scalate view) does not have to specify viewPaths.
   */
  def wrap(app: App, routes: List[Route], errorRoutes: Map[String, String], controllerPaths: List[String], viewPaths: List[String] = List()) = {
    this.viewPaths = viewPaths

    compiledRoutes = routes.map(compileRoute(_, controllerPaths))

    val (csast404, ka404) = compileCsas(errorRoutes("404"), controllerPaths)
    val compiledCsas500   = compileCsas(errorRoutes("500"), controllerPaths)

    new App {
      def call(remoteIp: String, channel: Channel, request: HttpRequest, response: HttpResponse, env: Env) {
        val method   = env("request_method").asInstanceOf[HttpMethod]
        val pathInfo = env("path_info").asInstanceOf[String]
        val (ka, uriParams) = matchRoute(method, pathInfo) match {
          case Some((ka, uriParams)) =>
            (ka, uriParams)

          case None =>
            response.setStatus(HttpResponseStatus.NOT_FOUND)
            val uriParams = new java.util.LinkedHashMap[String, java.util.List[String]]()
            uriParams.put("controller", toValues(csast404._1))
            uriParams.put("action",     toValues(csast404._2))
            (ka404, uriParams)
        }

        // Put (csast500, ka500) to env so that they can be taken out later
        // by Failsafe midddleware
        env.put("error500", compiledCsas500)

        logger.debug(method + " " + pathInfo)  // TODO: Fix this ugly code (1 of 3)
        dispatch(app, remoteIp, channel, request, response, env, ka, uriParams)
      }
    }
  }

  /**
   * WARN: This method is here because it is also used by Failsafe when redispatching.
   */
  def dispatch(app: App,
               remoteIp: String, channel: Channel, request: HttpRequest, response: HttpResponse, env: Env,
               ka: KA, uriParams: UriParams) {
    // Merge uriParams to params
    val params = env("params").asInstanceOf[UriParams]
    params.putAll(uriParams)

    // Put controller (Controller) and action (Method) to env so that
    // the action can be invoked at XTApp
    val (k, a) = ka
    val c = k.newInstance
    env.put("controller", c)
    env.put("action",     a)

    logger.debug(filterParams(params).toString)  // TODO: Fix this ugly code (2 of 3)
    val t1 = System.currentTimeMillis
    app.call(remoteIp, channel, request, response, env)
    val t2 = System.currentTimeMillis
    logger.debug((t2 - t1) + " [ms]")            // TODO: Fix this ugly code (3 of 3)
  }

  /**
   * WARN: This method is here because it is also used by Failsafe when redispatching.
   *
   * Wraps a single String by a List.
   */
  def toValues(value: String): java.util.List[String] = {
    val values = new java.util.ArrayList[String](1)
    values.add(value)
    values
  }

  //----------------------------------------------------------------------------

  private def compileRoute(route: Route, controllerPaths: List[String]): CompiledRoute = {
    val (method, pattern, csas) = route
    val cp = compilePattern(pattern)
    val (csast, ka) = compileCsas(csas, controllerPaths)
    (method, cp, csast, ka)
  }

  private def compilePattern(pattern: String): CompiledPattern = {
    val tokens = pattern.split("/").filter(_ != "")
    tokens.map { e: String =>
      val constant = !e.startsWith(":")
      val token    = if (constant) e else e.substring(1)
      (token, constant)
    }
  }

  /**
   * Given "Articles#index", rerturns:
   * ("Articles", "index", Articles class, index method)
   */
  private def compileCsas(csas: String, controllerPaths: List[String]): CompiledCsas = {
    val caa = csas.split("#")
    val cs  = caa(0)
    val as  = caa(1)

    var k: Class[Controller] = null
    controllerPaths.find { p =>
      try {
        k = Class.forName(p + "." + cs).asInstanceOf[Class[Controller]]
        true
      } catch {
        case _ => false
      }
    }
    if (k == null) throw(new Exception("Could not load " + csas))

    val a  = k.getMethod(as)

    ((cs, as), (k, a))
  }

  /**
   * Returns None if not matched.
   */
  private def matchRoute(method: HttpMethod, pathInfo: String): Option[(KA, UriParams)] = {
    val tokens = pathInfo.split("/").filter(_ != "")
    val max1   = tokens.size

    var uriParams: UriParams = null

    val finder = (cr: CompiledRoute) => {
      val (m, compiledPattern, csas, compiledCA) = cr

      // Must be <= max1
      // If < max1, the last token must be "*" and non-fixed
      val max2 = compiledPattern.size

      if (max2 > max1 || m != method)
        false
      else {
        if (max2 == 0) {
          if (max1 == 0) {
            uriParams = new java.util.LinkedHashMap[String, java.util.List[String]]()
            true
          } else
            false
        } else {
          uriParams = new java.util.LinkedHashMap[String, java.util.List[String]]()
          var i = 0  // i will go from 0 until max1

          compiledPattern.forall { tc =>
            val (token, constant) = tc
            val ret = if (constant)
              (token == tokens(i))
            else {
              if (i == max2 - 1) {
                if (token == "*") {
                  val value = tokens.slice(i, max1).mkString("/")
                  uriParams.put(token, toValues(value))
                  true
                } else {
                  if (max2 < max1) {
                    false
                  } else {  // max2 = max1
                    val value = tokens(i)
                    uriParams.put(token, toValues(value))
                    true
                  }
                }
              } else {  // Not the last token
                if (token == "*") {
                  false
                } else {
                  URLDecoder.decode(tokens(i)) match {
                    case None => false

                    case Some(value) =>
                      uriParams.put(token, toValues(value))
                      true
                  }
                }
              }
            }

            i += 1
            ret
          }
        }
      }
    }

    compiledRoutes.find(finder) match {
      case Some(cr) =>
        val (m, compiledPattern, csas, compiledKA) = cr
        val (cs, as) = csas
        uriParams.put("controller", toValues(cs))
        uriParams.put("action",     toValues(as))
        Some((compiledKA, uriParams))

      case None => None
    }
  }

  // Same as Rails' config.filter_parameters
  private def filterParams(params: java.util.Map[String, java.util.List[String]]): java.util.Map[String, java.util.List[String]] = {
    val ret = new java.util.LinkedHashMap[String, java.util.List[String]]()
    ret.putAll(params)
    for (key <- Config.filterParams) {
      if (ret.containsKey(key)) ret.put(key, toValues("[filtered]"))
    }
    ret
  }
}
