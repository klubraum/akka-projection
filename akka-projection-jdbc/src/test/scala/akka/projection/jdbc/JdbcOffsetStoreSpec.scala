/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.jdbc

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.existentials
import scala.util.Try

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.japi.function
import akka.persistence.query.Sequence
import akka.projection.MergeableOffset
import akka.projection.ProjectionId
import akka.projection.StringKey
import akka.projection.TestTags
import akka.projection.jdbc.JdbcOffsetStoreSpec.JdbcSpecConfig
import akka.projection.jdbc.JdbcOffsetStoreSpec.MySQLSpecConfig
import akka.projection.jdbc.internal.Dialect
import akka.projection.jdbc.internal.JdbcOffsetStore
import akka.projection.jdbc.internal.JdbcSessionUtil.tryWithResource
import akka.projection.jdbc.internal.JdbcSessionUtil.withConnection
import akka.projection.jdbc.internal.JdbcSettings
import akka.projection.testkit.internal.TestClock
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.OptionValues
import org.scalatest.Tag
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy

object JdbcOffsetStoreSpec {

  trait JdbcSpecConfig {
    val name: String
    def tag: Tag
    val baseConfig = ConfigFactory.parseString("""
    
    akka {
      loglevel = "DEBUG"
      projection.jdbc = {
        offset-store {
          schema = ""
          table = "AKKA_PROJECTION_OFFSET_STORE"
        }
        
        blocking-jdbc-dispatcher.thread-pool-executor.fixed-pool-size = 5
        debug.verbose-offset-store-logging = true
      }
    }
    """)
    val config: Config
    def jdbcSessionFactory(): JdbcSession

    def initContainer(): Unit
    def stopContainer(): Unit
  }

  private[projection] class PureJdbcSession(connFunc: () => Connection) extends JdbcSession {

    lazy val conn = connFunc()
    override def withConnection[Result](func: function.Function[Connection, Result]): Result =
      func(conn)

    override def commit(): Unit = conn.commit()

    override def rollback(): Unit = conn.rollback()

    override def close(): Unit = conn.close()
  }

  object H2SpecConfig extends JdbcSpecConfig {

    val name = "H2 Database"
    val tag: Tag = TestTags.InMemoryDb

    override val config: Config =
      baseConfig.withFallback(ConfigFactory.parseString("""
        akka.projection.jdbc = {
          dialect = "h2-dialect"
        }
        """))

    def jdbcSessionFactory(): PureJdbcSession =
      new PureJdbcSession(() => {
        Class.forName("org.h2.Driver")
        val conn = DriverManager.getConnection("jdbc:h2:mem:offset-store-test-jdbc;DB_CLOSE_DELAY=-1")
        conn.setAutoCommit(false)
        conn
      })

    override def initContainer() = ()

    override def stopContainer() = ()

  }

  abstract class ContainerJdbcSpecConfig(dialect: String) extends JdbcSpecConfig {

    val tag: Tag = TestTags.ContainerDb

    override val config: Config =
      baseConfig.withFallback(ConfigFactory.parseString(s"""
        akka.projection.jdbc = {
          dialect = $dialect 
        }
        """))

    def jdbcSessionFactory(): PureJdbcSession = {

      // this is safe as tests only start after the container is init
      val container = _container.get

      new PureJdbcSession(() => {
        Class.forName(container.getDriverClassName)
        val conn =
          DriverManager.getConnection(container.getJdbcUrl, container.getUsername, container.getPassword)
        conn.setAutoCommit(false)
        conn
      })
    }

    protected var _container: Option[JdbcDatabaseContainer[_]] = None

    def newContainer(): JdbcDatabaseContainer[_]

    final override def initContainer(): Unit = {
      val container = newContainer()
      _container = Some(container)
      container.withStartupCheckStrategy(new IsRunningStartupCheckStrategy)
      container.withStartupAttempts(5)
      container.start()
    }

    override def stopContainer(): Unit =
      _container.get.stop()
  }

  object PostgresSpecConfig extends ContainerJdbcSpecConfig("postgres-dialect") {
    val name = "Postgres Database"
    override def newContainer() = new PostgreSQLContainer
  }

  object MySQLSpecConfig extends ContainerJdbcSpecConfig("mysql-dialect") {
    val name = "MySQL Database"
    override def newContainer() = new MySQLContainer
  }

  object MSSQLServerSpecConfig extends ContainerJdbcSpecConfig("mssql-dialect") {
    val name = "MS SQL Server Database"
    override val tag: Tag = TestTags.FlakyDb
    override def newContainer() = new MSSQLServerContainer
  }

  object OracleSpecConfig extends ContainerJdbcSpecConfig("oracle-dialect") {
    val name = "Oracle Database"
    override def newContainer() = new OracleContainer("oracleinanutshell/oracle-xe-11g")
  }

}

class H2JdbcOffsetStoreSpec extends JdbcOffsetStoreSpec(JdbcOffsetStoreSpec.H2SpecConfig)
class PostgresJdbcOffsetStoreSpec extends JdbcOffsetStoreSpec(JdbcOffsetStoreSpec.PostgresSpecConfig)
class MySQLJdbcOffsetStoreSpec extends JdbcOffsetStoreSpec(JdbcOffsetStoreSpec.MySQLSpecConfig)
class MSSQLServerJdbcOffsetStoreSpec extends JdbcOffsetStoreSpec(JdbcOffsetStoreSpec.MSSQLServerSpecConfig)
class OracleJdbcOffsetStoreSpec extends JdbcOffsetStoreSpec(JdbcOffsetStoreSpec.OracleSpecConfig)

abstract class JdbcOffsetStoreSpec(specConfig: JdbcSpecConfig)
    extends ScalaTestWithActorTestKit(specConfig.config)
    with AnyWordSpecLike
    with LogCapturing
    with OptionValues {

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(3, Seconds), interval = Span(100, Millis))

  implicit val executionContext: ExecutionContextExecutor = testKit.system.executionContext

  // test clock for testing of the `last_updated` Instant
  private val clock = new TestClock

  private val settings = JdbcSettings(testKit.system)
  private val offsetStore = new JdbcOffsetStore(system, settings, specConfig.jdbcSessionFactory _, clock)
  private val dialectLabel = specConfig.name

  override protected def beforeAll(): Unit = {
    // start test container if needed
    // Note, the H2 test don't run in container and are therefore will run must faster
    // wrapping Future to at least be able to add a timeout
    Await.result(Future.fromTry(Try(specConfig.initContainer())), 30.seconds)

    // create offset table
    Await.result(offsetStore.createIfNotExists(), 30.seconds)
  }

  override protected def afterAll(): Unit =
    specConfig.stopContainer()

  private def selectLastUpdated(projectionId: ProjectionId): Instant = {
    withConnection(specConfig.jdbcSessionFactory _) { conn =>

      val statement = {
        val stmt = s"""SELECT * FROM "${settings.table}" WHERE "PROJECTION_NAME" = ? AND "PROJECTION_KEY" = ?"""
        specConfig match {
          case MySQLSpecConfig => Dialect.removeQuotes(stmt)
          case _               => stmt
        }
      }

      // init statement in try-with-resource
      tryWithResource(conn.prepareStatement(statement)) { stmt =>
        stmt.setString(1, projectionId.name)
        stmt.setString(2, projectionId.key)

        // init ResultSet in try-with-resource
        tryWithResource(stmt.executeQuery()) { resultSet =>

          if (resultSet.next()) {
            val t = resultSet.getTimestamp(6)
            Instant.ofEpochMilli(t.getTime)
          } else throw new RuntimeException(s"no records found for $projectionId")
        }
      }
    }.futureValue
  }

  private def genRandomProjectionId() = ProjectionId(UUID.randomUUID().toString, "00")

  "The JdbcOffsetStore" must {

    s"create and update offsets [$dialectLabel]" taggedAs (specConfig.tag) in {

      val projectionId = genRandomProjectionId()

      withClue("check - save offset 1L") {
        offsetStore.saveOffset(projectionId, 1L).futureValue
      }

      withClue("check - read offset") {
        val offset = offsetStore.readOffset[Long](projectionId)
        offset.futureValue shouldBe Some(1L)
      }

      withClue("check - save offset 2L") {
        offsetStore.saveOffset(projectionId, 2L).futureValue
      }

      withClue("check - read offset after overwrite") {
        val offset = offsetStore.readOffset[Long](projectionId)
        offset.futureValue shouldBe Some(2L) // yep, saveOffset overwrites previous
      }

    }

    s"save and retrieve offsets of type Long [$dialectLabel]" taggedAs (specConfig.tag) in {

      val projectionId = genRandomProjectionId()

      withClue("check - save offset") {
        offsetStore.saveOffset(projectionId, 1L).futureValue
      }

      withClue("check - read offset") {
        val offset = offsetStore.readOffset[Long](projectionId)
        offset.futureValue shouldBe Some(1L)
      }

    }

    s"save and retrieve offsets of type java.lang.Long [$dialectLabel]" taggedAs (specConfig.tag) in {

      val projectionId = genRandomProjectionId()
      withClue("check - save offset") {
        offsetStore.saveOffset(projectionId, java.lang.Long.valueOf(1L)).futureValue
      }

      withClue("check - read offset") {
        val offset = offsetStore.readOffset[java.lang.Long](projectionId)
        offset.futureValue shouldBe Some(1L)
      }
    }

    s"save and retrieve offsets of type Int [$dialectLabel]" taggedAs (specConfig.tag) in {

      val projectionId = genRandomProjectionId()
      withClue("check - save offset") {
        offsetStore.saveOffset(projectionId, 1).futureValue
      }

      withClue("check - read offset") {
        val offset = offsetStore.readOffset[Int](projectionId)
        offset.futureValue shouldBe Some(1)
      }

    }

    s"save and retrieve offsets of type java.lang.Integer [$dialectLabel]" taggedAs (specConfig.tag) in {

      val projectionId = genRandomProjectionId()

      withClue("check - save offset") {
        offsetStore.saveOffset(projectionId, java.lang.Integer.valueOf(1)).futureValue
      }

      withClue("check - read offset") {
        val offset = offsetStore.readOffset[java.lang.Integer](projectionId)
        offset.futureValue shouldBe Some(1)
      }
    }

    s"save and retrieve offsets of type String [$dialectLabel]" taggedAs (specConfig.tag) in {

      val projectionId = genRandomProjectionId()
      val randOffset = UUID.randomUUID().toString
      withClue("check - save offset") {
        offsetStore.saveOffset(projectionId, randOffset).futureValue
      }

      withClue("check - read offset") {
        val offset = offsetStore.readOffset[String](projectionId)
        offset.futureValue shouldBe Some(randOffset)
      }
    }

    s"save and retrieve offsets of type akka.persistence.query.Sequence [$dialectLabel]" taggedAs (specConfig.tag) in {

      val projectionId = genRandomProjectionId()

      val seqOffset = Sequence(1L)
      withClue("check - save offset") {
        offsetStore.saveOffset(projectionId, seqOffset).futureValue
      }

      withClue("check - read offset") {
        val offset =
          offsetStore.readOffset[Sequence](projectionId).futureValue.value
        offset shouldBe seqOffset
      }
    }

    s"save and retrieve offsets of type akka.persistence.query.TimeBasedUUID [$dialectLabel]" taggedAs (specConfig.tag) in {

      val projectionId = genRandomProjectionId()

      withClue("check - save offset") {
        offsetStore.saveOffset(projectionId, 1L).futureValue
      }

      withClue("check - read offset") {
        val offset = offsetStore.readOffset[Long](projectionId)
        offset.futureValue shouldBe Some(1L)
      }
    }

    s"save and retrieve MergeableOffset [$dialectLabel]" taggedAs (specConfig.tag) in {

      val projectionId = genRandomProjectionId()

      val origOffset = MergeableOffset(Map(StringKey("abc") -> 1L, StringKey("def") -> 1L, StringKey("ghi") -> 1L))
      withClue("check - save offset") {
        offsetStore.saveOffset(projectionId, origOffset).futureValue
      }

      withClue("check - read offset") {
        val offset = offsetStore.readOffset[MergeableOffset[StringKey, Long]](projectionId)
        offset.futureValue shouldBe Some(origOffset)
      }
    }

    s"add new offsets to MergeableOffset [$dialectLabel]" taggedAs (specConfig.tag) in {

      val projectionId = genRandomProjectionId()

      val origOffset = MergeableOffset(Map(StringKey("abc") -> 1L, StringKey("def") -> 1L))
      withClue("check - save offset") {
        offsetStore.saveOffset(projectionId, origOffset).futureValue
      }

      withClue("check - read offset") {
        val offset = offsetStore.readOffset[MergeableOffset[StringKey, Long]](projectionId)
        offset.futureValue shouldBe Some(origOffset)
      }

      // mix updates and inserts
      val updatedOffset = MergeableOffset(Map(StringKey("abc") -> 2L, StringKey("def") -> 2L, StringKey("ghi") -> 1L))
      withClue("check - save offset") {
        offsetStore.saveOffset(projectionId, updatedOffset).futureValue
      }

      withClue("check - read offset") {
        val offset = offsetStore.readOffset[MergeableOffset[StringKey, Long]](projectionId)
        offset.futureValue shouldBe Some(updatedOffset)
      }
    }

    s"update timestamp [$dialectLabel]" taggedAs (specConfig.tag) in {

      val projectionId = genRandomProjectionId()

      val instant0 = clock.instant()
      offsetStore.saveOffset(projectionId, 15).futureValue

      val instant1 = selectLastUpdated(projectionId)
      instant1 shouldBe instant0

      val instant2 = clock.tick(java.time.Duration.ofMillis(5))
      offsetStore.saveOffset(projectionId, 16).futureValue

      val instant3 = selectLastUpdated(projectionId)
      instant3 shouldBe instant2
    }

    s"clear offset [$dialectLabel]" taggedAs (specConfig.tag) in {

      val projectionId = genRandomProjectionId()

      withClue("check - save offset") {
        offsetStore.saveOffset(projectionId, 3L).futureValue
      }

      withClue("check - read offset") {
        offsetStore.readOffset[Long](projectionId).futureValue shouldBe Some(3L)
      }

      withClue("check - clear offset") {
        offsetStore.clearOffset(projectionId).futureValue
      }

      withClue("check - read offset") {
        offsetStore.readOffset[Long](projectionId).futureValue shouldBe None
      }
    }
  }
}
