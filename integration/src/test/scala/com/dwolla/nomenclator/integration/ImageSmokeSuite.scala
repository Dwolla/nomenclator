package com.dwolla.nomenclator.integration

import cats.effect.*
import cats.syntax.all.*
import com.dimafeng.testcontainers.GenericContainer
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.model.Capability
import com.dimafeng.testcontainers.GenericContainer.FileSystemBind
import com.dwolla.nomenclator.consul.agent.*
import com.dwolla.nomenclator.model.*
import io.circe.jawn
import munit.CatsEffectSuite
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.output.OutputFrame.OutputType
import org.testcontainers.containers.wait.strategy.Wait

import java.net.{InetSocketAddress, ServerSocket}
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using

/** Runs the container image the way ECS will, with production's hardening (see
  * [[HardenedContainer]]), against a real Consul agent, a real OpenTelemetry
  * Collector, and the fakes of [[SmokeEnvironment]] (which bind to the host's
  * loopback, which a host-network container shares). Skipped unless
  * `NOMENCLATOR_IMAGE` names the image; `scripts/check-image` builds it and
  * sets that.
  *
  * Because the binary only has the reflection and resources the agent run of
  * `scripts/native-image-metadata` exercised, every test fails on the errors a
  * missing registration produces.
  */
class ImageSmokeSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 3.minutes

  // Read from the file that scripts/native-image-metadata reads too, so the two can't drift apart.
  private val collectorImage =
    Using.resource(Source.fromResource("otel-collector.image"))(_.mkString.trim)

  // The JDK prints this on the first call of sun.misc.Unsafe::objectFieldOffset, which the Scala 3 runtime of some
  // dependencies (compiled before Scala 3.8) makes. Native-image has no documented switch for it, so it is the one
  // output that isn't JSON, and the tests pin it to notice any other.
  private val unsafeWarning = List(
    "WARNING: A terminally deprecated method in sun.misc.Unsafe has been called",
    "WARNING: sun.misc.Unsafe::objectFieldOffset has been called by scala.runtime.LazyVals$ (file:/app/nomenclator)",
    "WARNING: Please consider reporting this to the maintainers of class scala.runtime.LazyVals$",
    "WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release"
  )

  private val missingMetadata = List(
    "MissingReflectionRegistrationError",
    "ClassNotFoundException",
    "NoSuchMethodException",
    "UnsatisfiedLinkError"
  )

  // The debug exporter prints each span as a block with a `Name           : <span name>` line.
  private val reconcileSpan = """(?m)^\s*Name\s*: reconcile\s*$""".r

  private def image: String = sys.env("NOMENCLATOR_IMAGE")

  private def assumeImage(): Unit =
    assume(sys.env.contains("NOMENCLATOR_IMAGE"), "set by scripts/check-image")

  private def started[A <: GenericContainer](c: A): Resource[IO, A] =
    Resource.make(IO.blocking:
      c.start()
      c)(c => IO.blocking(c.stop()))

  private def loopback(port: Int): String = s"127.0.0.1:$port"

  private final case class Stack(
                                  consul: ConsulAgent[IO],
                                  consulContainer: GenericContainer,
                                  consulPort: Int,
                                  collector: GenericContainer,
                                  ecsAgent: Uri,
                                  credentials: Uri,
                                  aws: Uri
  ):
    def environment: Map[String, String] = Map(
      "NOMENCLATOR_MODE" -> "apply",
      "NOMENCLATOR_INTERVAL" -> "1s",
      "NOMENCLATOR_HOSTNAME" -> FixtureServices.hostName.value,
      "NOMENCLATOR_ADDRESS" -> FixtureServices.address.value,
      "NOMENCLATOR_ECS_AGENT_URL" -> ecsAgent.renderString,
      "AWS_CONTAINER_CREDENTIALS_FULL_URI" -> credentials.renderString,
      "AWS_ENDPOINT_URL_ECS" -> aws.renderString,
      "CONSUL_HTTP_ADDR" -> loopback(consulPort),
      "OTEL_EXPORTER_OTLP_ENDPOINT" ->
        s"http://${loopback(collector.mappedPort(4317))}",
      "OTEL_BSP_SCHEDULE_DELAY" -> "500",
      "OTEL_METRIC_EXPORT_INTERVAL" -> "1000"
    )

    def reconcileSpans: Int =
      reconcileSpan
        .findAllIn(collector.logs)
        .size

    /** Stops the Consul agent's container, keeping it to start again.
      */
    def stopConsul: IO[Unit] =
      IO.blocking(
        consulContainer.dockerClient
          .stopContainerCmd(consulContainer.containerId)
          .exec()
      ).void

    /** Starts the stopped Consul agent's container again, on the same port, and
      * waits until the agent answers. An agent in dev mode keeps nothing across
      * a restart, so it starts with no services.
      */
    def startConsul: IO[Unit] =
      IO.blocking(
        consulContainer.dockerClient
          .startContainerCmd(consulContainer.containerId)
          .exec()
      ) >> ConsulContainer.awaitReady(consul)
  end Stack

  /** A host port that nothing listens on, for Consul. It is chosen rather than
    * left to Docker because Docker picks again when the outage test starts
    * Consul again. It is chosen below the ephemeral port ranges (macOS's from
    * 49152, Docker Desktop's VM's from 55000, Linux's default from 32768), so
    * no outgoing connection can take it before Docker binds it. Each port is
    * probed as Docker binds it: on every address, and without SO_REUSEADDR,
    * which lets a probe succeed beside a socket that Docker's bind would
    * collide with. A new ServerSocket's SO_REUSEADDR is up to the platform, and
    * on macOS it is on.
    */
  private val unusedConsulPort: IO[Int] =
    def unused(port: Int): Boolean =
      Using(new ServerSocket()): socket =>
        socket.setReuseAddress(false)
        socket.bind(new InetSocketAddress(port))
      .isSuccess
    IO.blocking(
      (20000 until 32768)
        .find(unused)
        .getOrElse(sys.error("no unused port from 20000 to 32767"))
    )

  private val stack: Resource[IO, Stack] =
    for
      consulPort <- unusedConsulPort.toResource
      consulContainer <- ConsulContainer.container(
        ConsulVersions.current,
        None,
        consulPort.some
      )
      client <- EmberClientBuilder.default[IO].build
      consul = Http4sConsulAgent(
        client,
        ConsulContainer.baseUri(consulContainer),
        None
      )
      _ <- ConsulContainer.awaitReady(consul).toResource
      collector <- started(
        GenericContainer(
          dockerImage = collectorImage,
          exposedPorts = Seq(4317),
          command = Seq("--config", "/etc/otelcol/config.yaml"),
          classpathResourceMapping = Seq(
            FileSystemBind(
              "otel-collector.yaml",
              "/etc/otelcol/config.yaml",
              BindMode.READ_ONLY
            )
          ),
          waitStrategy = Wait.forLogMessage(".*Everything is ready.*", 1)
        )
      )
      ecsAgent <- FakeEcsAgent.resource
      aws <- FakeAws.resource
    yield Stack(
      consul,
      consulContainer,
      consulPort,
      collector,
      ecsAgent.base,
      aws.credentials,
      aws.base
    )

  /** Retries `check` twice a second until it holds, or fails with its last
    * failure after `within`.
    */
  private def eventually[A](within: FiniteDuration)(check: => A): IO[A] =
    def attempt(remaining: Int): IO[A] =
      IO.blocking(check)
        .handleErrorWith:
          case _: Throwable if remaining > 0 =>
            IO.sleep(500.millis) >> attempt(remaining - 1)
          case e => IO.raiseError(e)
    attempt((within / 500.millis).toInt)

  private def serviceIds(s: Stack): IO[Set[ServiceId]] =
    s.consul.state.map(
      _.services.map(_.id).filterNot(_ == ServiceId("consul")).toSet
    )

  private def awaitExpectedServices(s: Stack): IO[Unit] =
    def attempt(remaining: Int): IO[Unit] =
      serviceIds(s).flatMap:
        case ids if ids == FixtureServices.expectedIds => IO.unit
        case ids if remaining > 0                      =>
          IO.sleep(500.millis) >> attempt(remaining - 1)
        case ids =>
          IO(
            fail(
              s"Consul's services after 30 s were $ids, not ${FixtureServices.expectedIds}"
            )
          )
    attempt(60)

  private def stdout(c: GenericContainer): String = c.logs(OutputType.STDOUT)
  private def stderr(c: GenericContainer): String = c.logs(OutputType.STDERR)

  private def lines(output: String): List[String] =
    output.linesIterator.filter(_.nonEmpty).toList

  private def isJson(line: String): Boolean = jawn.parse(line).isRight

  /** How many of Nomenclator's log lines have a message starting `prefix`.
    */
  private def loggedCount(c: GenericContainer, prefix: String): Int =
    lines(stdout(c))
      .flatMap(jawn.parse(_).toOption)
      .flatMap(_.hcursor.get[String]("message").toOption)
      .count(_.startsWith(prefix))

  /** The differences Nomenclator's shadow report logged as appearing, as the
    * kind of difference and the service ID.
    */
  private def shadowDifferences(c: GenericContainer): Set[(String, String)] =
    lines(stdout(c))
      .flatMap(jawn.parse(_).toOption)
      .filter(
        _.hcursor
          .get[String]("message")
          .contains("Shadow difference appeared")
      )
      .flatMap: j =>
        val mdc = j.hcursor.downField("mdc")
        (
          mdc.get[String]("difference").toOption,
          mdc.get[String]("service_id").toOption
        ).tupled
      .toSet

  /** Waits until Nomenclator has logged more than `before` lines whose message
    * starts `prefix`.
    */
  private def awaitLogged(c: GenericContainer, prefix: String, before: Int): IO[Unit] =
    eventually(60.seconds)(
      assert(
        loggedCount(c, prefix) > before,
        s"no new line starting `$prefix` in:\n${stdout(c)}"
      )
    )

  /** Nomenclator logs JSON to stdout, so every stdout line must parse. The only
    * other output allowed is the Unsafe warning, and only on stderr.
    */
  private def assertOnlyExpectedOutput(c: GenericContainer): Unit =
    assertEquals(lines(stdout(c)).filterNot(isJson), Nil)
    assertEquals(lines(stderr(c)).filterNot(isJson), unsafeWarning)

  /** The HTTP status of each failed task definition lookup that Nomenclator
    * logged as an unrecognized answer.
    */
  private def lookupFailureStatuses(c: GenericContainer): List[Int] =
    lines(stdout(c))
      .flatMap(jawn.parse(_).toOption)
      .filter(
        _.hcursor
          .get[String]("message")
          .exists(_.startsWith("Task definition lookup failed"))
      )
      .flatMap(_.hcursor.downField("mdc").get[String]("status").toOption)
      .flatMap(_.toIntOption)

  private def assertNoneOf(c: GenericContainer, needles: List[String]): Unit =
    val all = stdout(c) + stderr(c)
    needles.foreach(n => assert(!all.contains(n), s"the container's output mentions $n:\n$all"))

  private def inspect(c: GenericContainer) =
    IO.blocking(c.dockerClient.inspectContainerCmd(c.containerId).exec())

  /** Checks what Docker reports for the container, so that
    * [[HardenedContainer]] can't silently stop applying its settings.
    */
  private def assertHardened(info: InspectContainerResponse): Unit =
    val host = info.getHostConfig
    val memoryLimit = 80L * 1024 * 1024
    assertEquals(host.getNetworkMode, "host")
    assertEquals(host.getReadonlyRootfs.booleanValue, true)
    assertEquals(info.getConfig.getUser, "65532")
    assertEquals(host.getCapDrop.toList, List(Capability.ALL))
    assertEquals(host.getMemory.longValue, memoryLimit)
    assertEquals(host.getMemorySwap.longValue, memoryLimit)

  test(
    "the image registers the fixture's services and stays within its memory limit"
  ):
    assumeImage()
    stack.use: s =>
      started(HardenedContainer(image, s.environment)).use: c =>
        for
          _ <- awaitExpectedServices(s)
          registeredAt <- IO.blocking(s.reconcileSpans)
          _ <- eventually(30.seconds)(
            assert(s.reconcileSpans >= registeredAt + 3, "3 more passes")
          )
          info <- inspect(c)
          _ <- IO.blocking:
            assertHardened(info)
            assertEquals(info.getState.getRunning.booleanValue, true)
            assertEquals(info.getState.getOOMKilled.booleanValue, false)
            assertOnlyExpectedOutput(c)
            assert(lines(stdout(c)).nonEmpty, "no log lines on stdout")
            assertNoneOf(c, missingMetadata)
        yield ()

  test(
    "the image reports differences in shadow mode, its default, and writes nothing"
  ):
    assumeImage()
    stack.use: s =>
      val environment = s.environment - "NOMENCLATOR_MODE"
      started(HardenedContainer(image, environment)).use: c =>
        for
          _ <- eventually(30.seconds)(
            assertEquals(
              shadowDifferences(c),
              FixtureServices.expectedIds.map(id => "missing" -> id.value)
            )
          )
          reportedAt <- IO.blocking(s.reconcileSpans)
          _ <- eventually(30.seconds)(
            assert(s.reconcileSpans >= reportedAt + 3, "3 more passes")
          )
          ids <- serviceIds(s)
          _ <- IO.blocking:
            assertEquals(ids, Set.empty[ServiceId])
            assertOnlyExpectedOutput(c)
            assertNoneOf(c, missingMetadata)
        yield ()

  test("the image exports its spans and metrics"):
    assumeImage()
    stack.use: s =>
      started(HardenedContainer(image, s.environment)).use: _ =>
        eventually(30.seconds):
          val log = s.collector.logs
          assert(
            reconcileSpan.findFirstIn(log).isDefined,
            "the collector has no `reconcile` span"
          )
          assert(
            log.contains("nomenclator.reconcile.duration"),
            "the collector has no `nomenclator.reconcile.duration` metric"
          )

  test(
    "the image stops promptly on SIGTERM and leaves its services registered"
  ):
    assumeImage()
    stack.use: s =>
      started(HardenedContainer(image, s.environment)).use: c =>
        val graceSeconds = 10
        for
          _ <- awaitExpectedServices(s)
          before <- inspect(c)
          elapsed <- IO
            .blocking(
              c.dockerClient
                .stopContainerCmd(c.containerId)
                .withTimeout(graceSeconds)
                .exec()
            )
            .timed
            .map(_._1)
          after <- inspect(c)
          ids <- serviceIds(s)
          _ <- IO.blocking:
            val state = after.getState
            assertEquals(before.getState.getRunning.booleanValue, true)
            assertNoneOf(c, missingMetadata)
            assert(
              elapsed < graceSeconds.seconds,
              s"docker stop took $elapsed, so it needed the kill"
            )
            assert(
              Set(0L, 143L).contains(state.getExitCodeLong.longValue),
              s"exit code ${state.getExitCodeLong}"
            )
            assertEquals(ids, FixtureServices.expectedIds)
        yield ()
        end for

  test(
    "the image survives a Consul outage and registers its services again"
  ):
    assumeImage()
    stack.use: s =>
      started(HardenedContainer(image, s.environment)).use: c =>
        val failed = "Reading the Consul agent failed"
        val recovered = "Reading the Consul agent recovered"
        for
          _ <- awaitExpectedServices(s)
          failedBefore <- IO.blocking(loggedCount(c, failed))
          _ <- s.stopConsul
          _ <- awaitLogged(c, failed, failedBefore)
          recoveredBefore <- IO.blocking(loggedCount(c, recovered))
          _ <- s.startConsul
          _ <- awaitLogged(c, recovered, recoveredBefore)
          _ <- awaitExpectedServices(s)
          _ <- IO.blocking:
            assertOnlyExpectedOutput(c)
            assertNoneOf(c, missingMetadata)
        yield ()

  test("the image reaches the real ECS API over TLS"):
    assumeImage()
    stack.use: s =>
      val environment =
        (s.environment - "AWS_ENDPOINT_URL_ECS") + ("AWS_REGION" -> "us-east-1")
      started(HardenedContainer(image, environment)).use: c =>
        for
          // The credentials are made up, so AWS answers with its own 4xx (UnrecognizedClientException), which the
          // client can't name and logs as an UnknownErrorResponse with the status. A failed handshake would log
          // another error.
          statuses <- eventually(30.seconds):
            val statuses = lookupFailureStatuses(c)
            assert(
              statuses.nonEmpty,
              s"no lookup failure from AWS in:\n${stdout(c)}"
            )
            statuses
          _ <- IO.blocking:
            assert(
              statuses.forall(s => s >= 400 && s < 500),
              s"AWS answered with $statuses"
            )
            assertOnlyExpectedOutput(c)
            assertNoneOf(
              c,
              List("SSLHandshakeException", "CertificateException") ++
                missingMetadata
            )
        yield ()

  test("the image rejects a malformed setting quickly"):
    assumeImage()
    stack.use: s =>
      val environment = s.environment + ("NOMENCLATOR_INTERVAL" -> "bogus")
      started(HardenedContainer(image, environment)).use: c =>
        for
          state <- eventually(10.seconds):
            val state =
              c.dockerClient.inspectContainerCmd(c.containerId).exec().getState
            assert(!state.getRunning.booleanValue, "still running")
            state
          _ <- IO.blocking:
            assertNotEquals(state.getExitCodeLong.longValue, 0L)
            assert(
              lines(stdout(c)).exists(l =>
                jawn
                  .parse(l)
                  .toOption
                  .exists(j =>
                    j.hcursor.get[String]("level").contains("ERROR") &&
                      j.hcursor
                        .get[String]("message")
                        .exists(_.contains("NOMENCLATOR_INTERVAL"))
                  )
              ),
              s"no ERROR line naming NOMENCLATOR_INTERVAL in:\n${stdout(c)}"
            )
            assertOnlyExpectedOutput(c)
        yield ()
end ImageSmokeSuite
