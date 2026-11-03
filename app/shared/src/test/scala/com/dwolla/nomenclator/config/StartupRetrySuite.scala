package com.dwolla.nomenclator.config

import cats.effect.*
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger

import scala.concurrent.duration.*

class StartupRetrySuite extends CatsEffectSuite:
  private val retry = StartupRetry(
    interval = 5.seconds,
    attemptTimeout = 30.seconds,
    giveUpAfter = 2.minutes
  )

  test(
    "a resource whose acquisition fails is acquired again, and released once"
  ):
    given StructuredLogger[IO] = StructuredTestingLogger.impl[IO]()
    TestControl.executeEmbed(
      for
        attempts <- Ref.of[IO, Int](0)
        releases <- Ref.of[IO, Int](0)
        flaky = Resource.make(
          attempts
            .updateAndGet(_ + 1)
            .flatMap: n =>
              IO.raiseError(new RuntimeException(s"attempt $n failed"))
                .whenA(n < 3)
                .as(n)
        )(_ => releases.update(_ + 1))
        acquiredOn <- retry.acquire("a flaky thing")(flaky).use(IO.pure)
        released <- releases.get
      yield
        assertEquals(acquiredOn, 3)
        assertEquals(released, 1)
    )

  test("a resource that is never acquired gives up with a StartupError"):
    given StructuredLogger[IO] = StructuredTestingLogger.impl[IO]()
    TestControl.executeEmbed(
      retry
        .acquire("a missing thing")(
          Resource.eval(IO.raiseError[Unit](new RuntimeException("gone")))
        )
        .use_
        .attempt
        .map:
          case Left(e: StartupError) =>
            assert(e.getMessage.contains("a missing thing"), e.getMessage)
          case other => fail(s"expected a StartupError, got $other")
    )
end StartupRetrySuite
