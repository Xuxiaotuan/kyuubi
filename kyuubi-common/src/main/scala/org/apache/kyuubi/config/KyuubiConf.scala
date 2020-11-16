/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.config

import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._

import org.apache.kyuubi.{Logging, Utils}
import org.apache.kyuubi.service.authentication.{AuthTypes, SaslQOP}

case class KyuubiConf(loadSysDefault: Boolean = true) extends Logging {
  private val settings = new ConcurrentHashMap[String, String]()
  private lazy val reader: ConfigProvider = new ConfigProvider(settings)

  if (loadSysDefault) {
    loadFromMap()
  }

  private def loadFromMap(props: Map[String, String] = Utils.getSystemProperties): KyuubiConf = {
    for ((key, value) <- props if key.startsWith("kyuubi.") || key.startsWith("spark.")) {
      set(key, value)
    }
    this
  }

  def loadFileDefaults(): KyuubiConf = {
    val maybeConfigFile = Utils.getDefaultPropertiesFile()
    loadFromMap(Utils.getPropertiesFromFile(maybeConfigFile))
    this
  }

  def set[T](entry: ConfigEntry[T], value: T): KyuubiConf = {
    settings.put(entry.key, entry.strConverter(value))
    this
  }

  def set[T](entry: OptionalConfigEntry[T], value: T): KyuubiConf = {
    set(entry.key, entry.strConverter(Option(value)))
    this
  }

  def set(key: String, value: String): KyuubiConf = {
    require(key != null)
    require(value != null)
    settings.put(key, value)
    this
  }

  def setIfMissing[T](entry: ConfigEntry[T], value: T): KyuubiConf = {
    settings.putIfAbsent(entry.key, entry.strConverter(value))
    this
  }

  def get[T](config: ConfigEntry[T]): T = {
    config.readFrom(reader)
  }

  def getOption(key: String): Option[String] = Option(settings.get(key))

  /** unset a parameter from the configuration */
  def unset(key: String): KyuubiConf = {
    settings.remove(key)
    this
  }

  def unset(entry: ConfigEntry[_]): KyuubiConf = {
    unset(entry.key)
  }

  /** Get all parameters as map */
  def getAll: Map[String, String] = {
    settings.entrySet().asScala.map(x => (x.getKey, x.getValue)).toMap[String, String]
  }

  /**
   * Retrieve key-value pairs from [[KyuubiConf]] starting with `dropped.remainder`, and put them to
   * the result map with the `dropped` of key being dropped.
   * @param dropped first part of prefix which will dropped for the new key
   * @param remainder second part of the prefix which will be remained in the key
   */
  def getAllWithPrefix(dropped: String, remainder: String): Map[String, String] = {
    getAll.filter { case (k, _) => k.startsWith(s"$dropped.$remainder")}.map {
      case (k, v) => (k.substring(dropped.length + 1), v)
    }
  }

  /** Copy this object */
  override def clone: KyuubiConf = {
    val cloned = KyuubiConf(false)
    settings.entrySet().asScala.foreach { e =>
      cloned.set(e.getKey, e.getValue)
    }
    cloned
  }

  /**
   * This method is used to convert kyuubi configs to configs that Spark could identify.
   * - If the key is start with `spark.`, keep it AS IS as it is a Spark Conf
   * - If the key is start with `hadoop.`, it will be prefixed with `spark.hadoop.`
   * - Otherwise, the key will be added a `spark.` prefix
   * @return a map with spark specified configs
   */
  def toSparkPrefixedConf: Map[String, String] = {
    settings.entrySet().asScala.map { e =>
      val key = e.getKey
      if (key.startsWith("spark.")) {
        key -> e.getValue
      } else if (key.startsWith("hadoop.")) {
        "spark.hadoop." + key -> e.getValue
      } else {
        "spark." + key -> e.getValue
      }
    }.toMap
  }
}

object KyuubiConf {

  /** a custom directory that contains the [[KYUUBI_CONF_FILE_NAME]] */
  final val KYUUBI_CONF_DIR = "KYUUBI_CONF_DIR"
  /** the default file that contains kyuubi properties */
  final val KYUUBI_CONF_FILE_NAME = "kyuubi-defaults.conf"
  final val KYUUBI_HOME = "KYUUBI_HOME"

  val kyuubiConfEntries: java.util.Map[String, ConfigEntry[_]] =
    java.util.Collections.synchronizedMap(new java.util.HashMap[String, ConfigEntry[_]]())

  private def register(entry: ConfigEntry[_]): Unit = kyuubiConfEntries.synchronized {
    require(!kyuubiConfEntries.containsKey(entry.key),
      s"Duplicate SQLConfigEntry. ${entry.key} has been registered")
    kyuubiConfEntries.put(entry.key, entry)
  }

  def buildConf(key: String): ConfigBuilder = {
    new ConfigBuilder("kyuubi." + key).onCreate(register)
  }

  val EMBEDDED_ZK_PORT: ConfigEntry[Int] = buildConf("zookeeper.embedded.port")
    .doc("The port of the embedded zookeeper server")
    .version("1.0.0")
    .intConf
    .createWithDefault(2181)

  val EMBEDDED_ZK_TEMP_DIR: ConfigEntry[String] = buildConf("zookeeper.embedded.directory")
    .doc("The temporary directory for the embedded zookeeper server")
    .version("1.0.0")
    .stringConf
    .createWithDefault("embedded_zookeeper")

  val SERVER_PRINCIPAL: OptionalConfigEntry[String] = buildConf("kinit.principal")
    .doc("Name of the Kerberos principal.")
    .version("1.0.0")
    .stringConf
    .createOptional

  val SERVER_KEYTAB: OptionalConfigEntry[String] = buildConf("kinit.keytab")
    .doc("Location of Kyuubi server's keytab.")
    .version("1.0.0")
    .stringConf
    .createOptional

  val KINIT_INTERVAL: ConfigEntry[Long] = buildConf("kinit.interval")
    .doc("How often will Kyuubi server run `kinit -kt [keytab] [principal]` to renew the" +
      " local Kerberos credentials cache")
    .version("1.0.0")
    .timeConf
    .createWithDefaultString("PT1H")

  val KINIT_MAX_ATTEMPTS: ConfigEntry[Int] = buildConf("kinit.max.attempts")
    .doc("How many times will `kinit` process retry")
    .version("1.0.0")
    .intConf
    .createWithDefault(10)

  val OPERATION_IDLE_TIMEOUT: ConfigEntry[Long] = buildConf("operation.idle.timeout")
    .doc("Operation will be closed when it's not accessed for this duration of time")
    .version("1.0.0")
    .timeConf
    .createWithDefault(Duration.ofHours(3).toMillis)


  /////////////////////////////////////////////////////////////////////////////////////////////////
  //                              Frontend Service Configuration                                 //
  /////////////////////////////////////////////////////////////////////////////////////////////////

  val FRONTEND_BIND_HOST: OptionalConfigEntry[String] = buildConf("frontend.bind.host")
    .doc("Hostname or IP of the machine on which to run the frontend service.")
    .version("1.0.0")
    .stringConf
    .createOptional

  val FRONTEND_BIND_PORT: ConfigEntry[Int] = buildConf("frontend.bind.port")
    .doc("Port of the machine on which to run the frontend service.")
    .version("1.0.0")
    .intConf
    .checkValue(p => p == 0 || (p > 1024 && p < 65535), "Invalid Port number")
    .createWithDefault(10009)

  val FRONTEND_MIN_WORKER_THREADS: ConfigEntry[Int] = buildConf("frontend.min.worker.threads")
    .doc("Minimum number of threads in the of frontend worker thread pool for the frontend" +
      " service")
    .version("1.0.0")
    .intConf
    .createWithDefault(9)

  val FRONTEND_MAX_WORKER_THREADS: ConfigEntry[Int] = buildConf("frontend.max.worker.threads")
    .doc("Maximum number of threads in the of frontend worker thread pool for the frontend" +
      " service")
    .version("1.0.0")
    .intConf
    .createWithDefault(99)

  val FRONTEND_WORKER_KEEPALIVE_TIME: ConfigEntry[Long] =
    buildConf("frontend.worker.keepalive.time")
      .doc("Keep-alive time (in milliseconds) for an idle worker thread")
      .version("1.0.0")
      .timeConf
      .createWithDefault(Duration.ofSeconds(60).toMillis)

  val FRONTEND_MAX_MESSAGE_SIZE: ConfigEntry[Int] =
    buildConf("frontend.max.message.size")
      .doc("Maximum message size in bytes a Kyuubi server will accept.")
      .version("1.0.0")
      .intConf
      .createWithDefault(104857600)

  val FRONTEND_LOGIN_TIMEOUT: ConfigEntry[Long] =
    buildConf("frontend.login.timeout")
      .doc("Timeout for Thrift clients during login to the frontend service.")
      .version("1.0.0")
      .timeConf
      .createWithDefault(Duration.ofSeconds(20).toMillis)

  val FRONTEND_LOGIN_BACKOFF_SLOT_LENGTH: ConfigEntry[Long] =
    buildConf("frontend.backoff.slot.length")
      .doc("Time to back off during login to the frontend service.")
      .version("1.0.0")
      .timeConf
      .createWithDefault(Duration.ofMillis(100).toMillis)

  val AUTHENTICATION_METHOD: ConfigEntry[String] = buildConf("authentication")
    .doc("Client authentication types.<ul>" +
      " <li>NONE: no authentication check.</li>" +
      " <li>KERBEROS: Kerberos/GSSAPI authentication.</li>" +
      " <li>LDAP: Lightweight Directory Access Protocol authentication.</li></ul>")
    .version("1.0.0")
    .stringConf
    .transform(_.toUpperCase(Locale.ROOT))
    .checkValues(AuthTypes.values.map(_.toString))
    .createWithDefault(AuthTypes.NONE.toString)

  val AUTHENTICATION_LDAP_URL: OptionalConfigEntry[String] = buildConf("authentication.ldap.url")
    .doc("SPACE character separated LDAP connection URL(s).")
    .version("1.0.0")
    .stringConf
    .createOptional

  val AUTHENTICATION_LDAP_BASEDN: OptionalConfigEntry[String] =
    buildConf("authentication.ldap.base.dn")
      .doc("LDAP base DN.")
      .version("1.0.0")
      .stringConf
      .createOptional

  val AUTHENTICATION_LDAP_DOMAIN: OptionalConfigEntry[String] =
    buildConf("authentication.ldap.domain")
      .doc("LDAP base DN.")
      .version("1.0.0")
      .stringConf
      .createOptional

  val DELEGATION_KEY_UPDATE_INTERVAL: ConfigEntry[Long] =
    buildConf("delegation.key.update.interval")
      .doc("unused yet")
      .version("1.0.0")
      .timeConf
      .createWithDefault(Duration.ofDays(1).toMillis)

  val DELEGATION_TOKEN_MAX_LIFETIME: ConfigEntry[Long] =
    buildConf("delegation.token.max.lifetime")
      .doc("unused yet")
      .version("1.0.0")
      .timeConf
      .createWithDefault(Duration.ofDays(7).toMillis)

  val DELEGATION_TOKEN_GC_INTERVAL: ConfigEntry[Long] =
    buildConf("delegation.token.gc.interval")
      .doc("unused yet")
      .version("1.0.0")
      .timeConf
      .createWithDefault(Duration.ofHours(1).toMillis)

  val DELEGATION_TOKEN_RENEW_INTERVAL: ConfigEntry[Long] =
    buildConf("delegation.token.renew.interval")
      .doc("unused yet")
      .version("1.0.0")
      .timeConf
      .createWithDefault(Duration.ofDays(7).toMillis)

  val SASL_QOP: ConfigEntry[String] = buildConf("authentication.sasl.qop")
    .doc("Sasl QOP enable higher levels of protection for Kyuubi communication with clients.<ul>" +
      " <li>auth - authentication only (default)</li>" +
      " <li>auth-int - authentication plus integrity protection</li>" +
      " <li>auth-conf - authentication plus integrity and confidentiality protection. This is" +
      " applicable only if Kyuubi is configured to use Kerberos authentication.</li> </ul>")
    .version("1.0.0")
    .stringConf
    .checkValues(SaslQOP.values.map(_.toString))
    .transform(_.toLowerCase(Locale.ROOT))
    .createWithDefault(SaslQOP.AUTH.toString)

  /////////////////////////////////////////////////////////////////////////////////////////////////
  //                                 SQL Engine Configuration                                    //
  /////////////////////////////////////////////////////////////////////////////////////////////////

  val ENGINE_SPARK_MAIN_RESOURCE: OptionalConfigEntry[String] =
    buildConf("session.engine.spark.main.resource")
      .doc("The package used to create Spark SQL engine remote application. If it is undefined," +
        " Kyuubi will use the default")
      .version("1.0.0")
      .stringConf
      .createOptional

  val ENGINE_LOGIN_TIMEOUT: ConfigEntry[Long] = buildConf("session.engine.login.timeout")
    .doc("The timeout(ms) of creating the connection to remote sql query engine")
    .version("1.0.0")
    .timeConf
    .createWithDefault(Duration.ofSeconds(15).toMillis)

  val ENGINE_INIT_TIMEOUT: ConfigEntry[Long] = buildConf("session.engine.initialize.timeout")
    .doc("Timeout for starting the background engine, e.g. SparkSQLEngine.")
    .version("1.0.0")
    .timeConf
    .createWithDefault(Duration.ofSeconds(60).toMillis)

  val SESSION_CHECK_INTERVAL: ConfigEntry[Long] =
    buildConf("session.check.interval")
      .doc("The check interval for session timeout.")
      .timeConf
      .checkValue(_ > Duration.ofSeconds(3).toMillis, "Minimum 3 seconds")
      .createWithDefault(Duration.ofMinutes(5).toMillis)

  val SESSION_TIMEOUT: ConfigEntry[Long] =
    buildConf("session.timeout")
      .doc("session timeout, it will be closed when it's not accessed for this duration")
      .timeConf
      .checkValue(_ > Duration.ofSeconds(3).toMillis, "Minimum 3 seconds")
      .createWithDefault(Duration.ofHours(6).toMillis)

  val ENGINE_CHECK_INTERVAL: ConfigEntry[Long] =
    buildConf("engine.check.interval")
      .doc("The check interval for engine timeout")
      .timeConf
      .checkValue(_ > Duration.ofSeconds(3).toMillis, "Minimum 3 seconds")
      .createWithDefault(Duration.ofMinutes(10).toMillis)

  val ENGINE_IDLE_TIMEOUT: ConfigEntry[Long] =
    buildConf("engine.idle.timeout")
      .doc("engine timeout, it will be closed when it's not accessed for this duration")
      .timeConf
      .createWithDefault(Duration.ofMinutes(30L).toMillis)

}
