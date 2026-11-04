package com.dwolla.nomenclator.startup

import cats.effect.*
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import com.dwolla.nomenclator.consul.algebra.ConsulAgent
import com.dwolla.nomenclator.consul.model.*
import com.dwolla.nomenclator.ecs.algebra.EcsAgentIntrospection
import com.dwolla.nomenclator.ecs.model.{EcsTask, InstanceMetadata}
import com.dwolla.nomenclator.registration.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.{INFO, WARN}
import smithy4s.aws.AwsRegion

import scala.concurrent.duration.*

class StartupDiscoverySuite extends CatsEffectSuite:
  private def ok[A](either: Either[String, A]): A =
    either.fold(fail(_), identity)

  private val retry = StartupRetry(
    interval = 5.seconds,
    attemptTimeout = 30.seconds,
    giveUpAfter = 2.minutes
  )

  private val unavailable = new RuntimeException("connection refused")

  /** A Consul agent whose advertised address is the next of `answers`, then the
    * last of them forever. Nothing else is expected of it.
    */
  private def consulAnswering(answers: List[IO[ServiceAddress]]): IO[ConsulAgent[IO]] =
    Ref
      .of[IO, List[IO[ServiceAddress]]](answers)
      .map: remaining =>
        new ConsulAgent[IO]:
          override val advertisedAddress: IO[ServiceAddress] =
            remaining
              .modify:
                case next :: Nil  => (List(next), next)
                case next :: rest => (rest, next)
                case Nil          => (Nil, IO.raiseError(unavailable))
              .flatten
          override val state: IO[AgentState] = IO.raiseError(unexpected)
          override def register(r: Registration): IO[Unit] =
            IO.raiseError(unexpected)
          override def deregister(id: ServiceId): IO[Unit] =
            IO.raiseError(unexpected)

  private val unexpected = new IllegalStateException("not expected")

  private def ecsAgentWith(answer: IO[InstanceMetadata]) =
    new EcsAgentIntrospection[IO]:
      override val metadata: IO[InstanceMetadata] = answer
      override val tasks: IO[List[EcsTask]] = IO.raiseError(unexpected)

  private def instanceIn(arn: String) =
    InstanceMetadata("example", arn).pure[IO]

  test("NOMENCLATOR_HOSTNAME wins over the local host's name"):
    StartupDiscovery
      .hostName[IO](
        ok(HostName.from("configured")).some,
        IO.raiseError(unexpected)
      )
      .map(h => assertEquals(h.value, "configured"))

  test("without an override, the host is named as the local host is"):
    StartupDiscovery
      .hostName[IO](None, "ip-10-0-0-1".pure[IO])
      .map(h => assertEquals(h.value, "ip-10-0-0-1"))

  test(
    "a local host name that can't be used fails startup, saying how to fix it"
  ):
    StartupDiscovery
      .hostName[IO](None, "odd:name".pure[IO])
      .attempt
      .map:
        case Left(e: StartupError) =>
          assert(e.getMessage.contains("odd:name"), e.getMessage)
          assert(e.getMessage.contains("NOMENCLATOR_HOSTNAME"), e.getMessage)
        case other => fail(s"expected a StartupError, got $other")

  test(
    "a local host name that can't be found fails startup, saying how to fix it"
  ):
    StartupDiscovery
      .hostName[IO](
        None,
        IO.raiseError(new java.net.UnknownHostException("odd-host"))
      )
      .attempt
      .map:
        case Left(e: StartupError) =>
          assert(e.getMessage.contains("odd-host"), e.getMessage)
          assert(e.getMessage.contains("NOMENCLATOR_HOSTNAME"), e.getMessage)
        case other => fail(s"expected a StartupError, got $other")

  test("NOMENCLATOR_ADDRESS wins over the Consul agent's advertised address"):
    given StructuredLogger[IO] = StructuredTestingLogger.impl[IO]()
    consulAnswering(Nil).flatMap(consul =>
      StartupDiscovery
        .address[IO](ServiceAddress("10.0.0.9").some, consul, retry)
        .map(assertEquals(_, ServiceAddress("10.0.0.9")))
    )

  test(
    "an unreachable Consul agent is retried, with one warning and one recovery"
  ):
    val logger = StructuredTestingLogger.impl[IO]()
    given StructuredLogger[IO] = logger
    TestControl.executeEmbed(
      for
        consul <- consulAnswering(
          List(
            unavailable.raiseError,
            unavailable.raiseError,
            ServiceAddress("10.0.0.1").pure
          )
        )
        start <- IO.monotonic
        address <- StartupDiscovery.address[IO](None, consul, retry)
        took <- IO.monotonic.map(_ - start)
        logged <- logger.logged
      yield
        assertEquals(address, ServiceAddress("10.0.0.1"))
        assertEquals(took, 10.seconds)
        assertEquals(
          logged
            .collect:
              case w: WARN => w
            .size,
          1,
          logged
        )
        assertEquals(
          logged.collect:
            case i: INFO => i.message
          ,
          Vector("Read the Consul agent's advertised address after retrying"),
          logged
        )
    )

  test("a Consul agent that answers at once is read without a word"):
    val logger = StructuredTestingLogger.impl[IO]()
    given StructuredLogger[IO] = logger
    for
      consul <- consulAnswering(List(ServiceAddress("10.0.0.1").pure))
      _ <- StartupDiscovery.address[IO](None, consul, retry)
      logged <- logger.logged
    yield assertEquals(logged, Vector.empty)

  test("a Consul agent that never answers is given up on after the limit"):
    given StructuredLogger[IO] = StructuredTestingLogger.impl[IO]()
    TestControl.executeEmbed(
      for
        consul <- consulAnswering(List(IO.never))
        start <- IO.monotonic
        result <- StartupDiscovery.address[IO](None, consul, retry).attempt
        took <- IO.monotonic.map(_ - start)
      yield
        result match
          case Left(e: StartupError) =>
            assert(
              e.getMessage.contains("the Consul agent's advertised address"),
              e.getMessage
            )
          case other => fail(s"expected a StartupError, got $other")
        assert(took >= 2.minutes, took)
        assert(took <= 2.minutes + 30.seconds, took)
    )

  test("AWS_REGION wins over the container instance's region"):
    given StructuredLogger[IO] = StructuredTestingLogger.impl[IO]()
    StartupDiscovery
      .region[IO](
        AwsRegion("us-west-2").some,
        ecsAgentWith(IO.raiseError(unexpected)),
        retry
      )
      .map(assertEquals(_, AwsRegion("us-west-2")))

  test("without AWS_REGION, the region comes from the container instance ARN"):
    given StructuredLogger[IO] = StructuredTestingLogger.impl[IO]()
    StartupDiscovery
      .region[IO](
        None,
        ecsAgentWith(
          instanceIn(
            "arn:aws:ecs:eu-west-1:123456789012:container-instance/example/abc"
          )
        ),
        retry
      )
      .map(assertEquals(_, AwsRegion("eu-west-1")))

  test("with no region anywhere, startup fails saying how to fix it"):
    given StructuredLogger[IO] = StructuredTestingLogger.impl[IO]()
    StartupDiscovery
      .region[IO](None, ecsAgentWith(instanceIn("not-an-arn")), retry)
      .attempt
      .map:
        case Left(e: StartupError) =>
          assert(e.getMessage.contains("AWS_REGION"), e.getMessage)
          assert(e.getMessage.contains("not-an-arn"), e.getMessage)
        case other => fail(s"expected a StartupError, got $other")
end StartupDiscoverySuite
