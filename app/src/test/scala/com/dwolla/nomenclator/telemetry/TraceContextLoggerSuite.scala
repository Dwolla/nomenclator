package com.dwolla.nomenclator.telemetry

import cats.effect.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit
import org.typelevel.otel4s.trace.Tracer

class TraceContextLoggerSuite extends CatsEffectSuite:
  test("a line logged inside a span carries its trace and span IDs"):
    OtelJavaTestkit
      .inMemory[IO]()
      .use: testkit =>
        val underlying = StructuredTestingLogger.impl[IO]()
        for
          given Tracer[IO] <- testkit.tracerProvider.get("test")
          logger = TraceContextLogger[IO](underlying)
          expected <- Tracer[IO]
            .span("work")
            .use: span =>
              logger.info(Map("service_id" -> "a"))("inside") >>
                logger.warn("inside, without context") >>
                IO.pure(span.context)
          logged <- underlying.logged
        yield
          val ids = Map(
            "trace_id" -> expected.traceIdHex,
            "span_id" -> expected.spanIdHex
          )
          assertEquals(
            logged.toList.map(_.ctx),
            List(ids + ("service_id" -> "a"), ids)
          )
        end for

  test("a line logged outside any span carries no IDs"):
    OtelJavaTestkit
      .inMemory[IO]()
      .use: testkit =>
        val underlying = StructuredTestingLogger.impl[IO]()
        for
          given Tracer[IO] <- testkit.tracerProvider.get("test")
          _ <- TraceContextLogger[IO](underlying).error(Map("k" -> "v"))(
            "outside"
          )
          logged <- underlying.logged
        yield assertEquals(logged.toList.map(_.ctx), List(Map("k" -> "v")))

  test("the line's own context can't overwrite the trace IDs"):
    OtelJavaTestkit
      .inMemory[IO]()
      .use: testkit =>
        val underlying = StructuredTestingLogger.impl[IO]()
        for
          given Tracer[IO] <- testkit.tracerProvider.get("test")
          expected <- Tracer[IO]
            .span("work")
            .use: span =>
              TraceContextLogger[IO](underlying)
                .info(Map("trace_id" -> "forged"))("inside")
                .as(span.context.traceIdHex)
          logged <- underlying.logged
        yield assertEquals(
          logged.toList.flatMap(_.ctx.get("trace_id")),
          List(expected)
        )
end TraceContextLoggerSuite
