package com.observe.datageneration

import com.github.tototoshi.csv.CSVReader
import org.fusesource.scalate._
import org.apache.commons.math3.distribution._

import java.io.File
import java.time.ZonedDateTime
import java.util.{ Base64, Date }
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

// template handling
class SimTemplate(filename:String) {
  val helper = new TemplateHelper
  val templateEngine = new TemplateEngine
  templateEngine.bindings = List(
    Binding("data", "com.observe.datageneration.TemplateHelper", true),
    Binding("init", "Boolean", true))
  templateEngine.escapeMarkup = false
  templateEngine.allowReload = false
  templateEngine.allowCaching = true

  val output = templateEngine.layout(filename, Map("init" -> true, "data" -> helper))
  println(output)

  def generate() = templateEngine.layout(filename, Map("init" -> false, "data" -> helper))
}

// data generation
abstract class Sampleable(distribution: RealDistribution) {
  val minProbability = 0.000001
  val maxProbability = 0.999999

  val icp0 = distribution.inverseCumulativeProbability(minProbability)
  val icp1 = distribution.inverseCumulativeProbability(maxProbability)
  val icpDif = icp1 - icp0

  def sampleDistribution = {
    val sample = distribution.sample()
    if (sample < icp0) 0
    else if (sample > icp1) 1
    else (sample - icp0) / icpDif
  }

  def sample: String = throw new Exception("'sample' not implemented for this type of sampler. Did you mean 'sampleRow'?")
  def sampleRow: List[String] = throw new Exception("'sampleRow' not implemented for this type of sampler. Did you mean 'sample'?")
}

class IntSampler(distribution: RealDistribution,
                 min: Int = Int.MinValue,
                 max: Int = Int.MaxValue) extends Sampleable(distribution = distribution) {
  private val range = max - min
  override def sample: String = sampleInt.toString
  def sampleInt = (min.toDouble + (range.toDouble * sampleDistribution).round).toInt
}

class RealSampler(distribution: RealDistribution,
                  min: Double = Double.MinValue,
                  max: Double = Double.MaxValue,
                  precision: Int = 4) extends Sampleable(distribution = distribution) {
  private val range = max - min
  def truncateAt(n: Double, p: Int): Double = { val s = math pow (10, p); (math floor n * s) / s }
  override def sample: String = sampleReal.toString
  def sampleReal = truncateAt((min + (range * sampleDistribution)), precision)
}

class ArraySampler(distribution: RealDistribution,
                   values:Array[String]) extends Sampleable(distribution = distribution) {
  override def sample: String = values(((values.length-1).toDouble * sampleDistribution).round.toInt)
}

class BooleanSampler(distribution: RealDistribution) extends Sampleable(distribution = distribution) {
  override def sample: String = if (sampleBoolean) "true" else "false"
  def sampleBoolean: Boolean = if (sampleDistribution.round == 1) true else false
}

class CSVSampler(distribution: RealDistribution,
                 filename: String,
                 ignoreHeader: Boolean = false) extends Sampleable(distribution = distribution) {
  println(s"Reading CSV '${filename}'")
  val reader = CSVReader.open(new File(filename))
  val values = reader.all.toArray
  reader.close
  override def sampleRow: List[String] = {
    // this is technically incorrect but this particular application is not an exact science
    val idx = ((values.length-1).toDouble * sampleDistribution).round.toInt
    if (ignoreHeader && idx == 0) values(1)
    values(idx)
  }
  override def sample = {
    sampleRow(0)
  }
}

// template context class

class TemplateHelper {
  // internals
  var init:Boolean = true

  // sampling
  object distributions {
    def exponential = new ExponentialDistribution(1)
    def normal = new NormalDistribution()
    def logNormal = new LogNormalDistribution()
    def uniform = new UniformRealDistribution()
  }

  val samplers = new TrieMap[String, Sampleable]
  def register(name: String, sampler: Sampleable) = samplers.update(name, sampler)
  def sample(name:String) = samplers.get(name).get.sample
  def sampleRow(name:String) = samplers.get(name).get.sampleRow

  // session handling
  val sessionState = new TrieMap[String, TrieMap[String, Any]]
  var sessionSampler: Sampleable = null
  def sessionSetup(distribution: RealDistribution, maxSessions: Int) = {
    sessionSampler = new IntSampler(distribution, 0, maxSessions)
  }
  def sessionId(hashed: Boolean = false) = {
    val sessionId = sessionSampler.sample
    if (hashed) sha1(sessionId) else "session_" + sessionId
  }
  def state(sessionId: String) = {
    sessionState.putIfAbsent(sessionId, new TrieMap[String, Any])
    sessionState.get(sessionId).get
  }
  def state(sessionId:String, key:String): Any = {
    state(sessionId).get(key).get
  }

  // simple dynamic content generation
  import java.text.SimpleDateFormat
  import java.util.TimeZone
  val tz: TimeZone = TimeZone.getTimeZone("UTC")
  val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'")
  val dfs = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")

  def timestamp(): String = "%.6f".format(ZonedDateTime.now().toInstant.toEpochMilli.toDouble / 1000d)
  def iso8601Timestamp() = df.format(new Date())
  def iso8601TimestampSeconds() = dfs.format(new Date())

  // utilities
  val md = java.security.MessageDigest.getInstance("SHA-1")
  def sha1(s: String) = Base64.getEncoder.encodeToString(md.digest(s.getBytes))
  def getSystemProperty(key: String, defaultValue: String) = Option(System.getProperty(key)).getOrElse(defaultValue)
}

// testing templates from the command line

object TemplateTest extends App {
  val template =
    if (args.length == 0) "templates/test.ssp"
    else args(0)
  val simTemplate = new SimTemplate(template)
  var i = 0
  while(i < 10) {
    val output = simTemplate.generate
    println(output)
    i = i + 1
  }
}
