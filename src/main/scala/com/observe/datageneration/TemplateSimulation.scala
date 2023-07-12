// ------------------------------------------------------------
// com.observe.datageneration
// https://github.com/obs-gh-craigvitter/observe-data-generator
// Apache 2.0 License: https://github.com/obs-gh-craigvitter/observe-data-generator/blob/main/LICENSE
//
// Derived from https://github.com/humio/humio-ingest-load-test
// ------------------------------------------------------------

package com.observe.datageneration

import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZonedDateTime }
import java.util.concurrent.TimeUnit
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.fusesource.scalate.{ Binding, TemplateEngine }
import scala.concurrent.duration._
import scala.util.Random

object TemplateSimulation {
  val random = new Random()
  val eventsPerBatch = Option(System.getProperty("batchsize")).getOrElse("1").toInt
  val dataspaces = Option(System.getProperty("dataspaces")).getOrElse("1").toInt
  val templateFile = Option(System.getProperty("template")).getOrElse("templates/accesslog.ssp")
  val simTemplate = new SimTemplate(templateFile)
  def request(): String = (for (i <- 0 until eventsPerBatch) yield simTemplate.generate).mkString("")
}

import TemplateSimulation._
class TemplateSimulation extends Simulation {
  // TODO: Figure out what these do
  val dataspaceFeeder = Iterator.continually(Map("dataspace" -> ("dataspace" + random.nextInt(dataspaces))))
  val requestFeeder = Iterator.continually(Map("request" -> request()))

  // TODO: Reconfigure the application to take different params around events per second to generate
  // ------------------------------------------------------------
  // 
  // ------------------------------------------------------------
  val users = Option(System.getProperty("users")).getOrElse("1").toInt // Default to 1 user
  val meanPauseDurationMs = Option(System.getProperty("pausems")).getOrElse("1000").toInt

  // Application Run Time
  val timeInMinutes = Option(System.getProperty("time")).getOrElse("1").toInt // Default to 1 minute run time for testing
  
  // Application args for URL, Ingest endPoint, and Token
  val baseUrlString = Option(System.getProperty("baseurl")).getOrElse("https://www.observe.com")
  val endPoint = Option(System.getProperty("endPoint")).getOrElse("/v1/http")
  val token = Option(System.getProperty("token")).getOrElse("developer")

  // TODO: Verify this can be deleted from the code base
  // val baseUrls = baseUrlString.split(",").toList

  println(s"Configuration:\n")
  println(s"users=$users")
  println(s"time=$timeInMinutes minutes")
  println(s"token=$token")
  println(s"baseurls=$baseUrlString")
  println(s"endPoint=${endPoint}")
  println(s"FullUrl=$baseUrlString$endPoint")
  println(s"dataspaces=$dataspaces") 
  println(s"batchsize=$eventsPerBatch")
  println(s"template=$templateFile")
  println(s"meanPauseDurationMs=$meanPauseDurationMs")

  override def before(step: => Unit): Unit = super.before(step)

  val httpConf = http
    .baseUrls(baseUrlString)
    .contentTypeHeader("text/plain; charset=utf-8")
    .acceptHeader("application/json")
    .header("Content-Encoding", "gzip")
    .acceptEncodingHeader("*")
    .userAgentHeader("gatling client")
    .authorizationHeader(s"Bearer ${token}")

  val scn = scenario("HTTP ingestion")
    .during(timeInMinutes minutes) {
      feed(dataspaceFeeder).feed(requestFeeder)
        .exec(http("request_1")
          .post(endPoint)
          .body(StringBody("${request}"))
          .processRequestBody(gzipBody)
          .check(status.is(200))
        ).pause(Duration(meanPauseDurationMs, TimeUnit.MILLISECONDS))
    }

  setUp(
    scn.inject(
      rampUsers(users) during(5 seconds)
    )
      .exponentialPauses
      .protocols(httpConf)
  )
}
