package com.dwolla.nomenclator.settings

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.concurrent.duration.*

class GoDurationSuite extends ScalaCheckSuite:
  test("parses each unit"):
    assertEquals(GoDuration.parse("7ns"), Right(7.nanos))
    assertEquals(GoDuration.parse("7us"), Right(7.micros))
    assertEquals(GoDuration.parse("7µs"), Right(7.micros))
    assertEquals(GoDuration.parse("7ms"), Right(7.millis))
    assertEquals(GoDuration.parse("7s"), Right(7.seconds))
    assertEquals(GoDuration.parse("7m"), Right(7.minutes))
    assertEquals(GoDuration.parse("7h"), Right(7.hours))

  test("parses sequences, decimals, signs, and bare zero"):
    assertEquals(GoDuration.parse("1m30s"), Right(90.seconds))
    assertEquals(
      GoDuration.parse("1h2m3s4ms"),
      Right(1.hour + 2.minutes + 3.seconds + 4.millis)
    )
    assertEquals(GoDuration.parse("1.5s"), Right(1500.millis))
    assertEquals(GoDuration.parse(".5s"), Right(500.millis))
    assertEquals(GoDuration.parse("5.s"), Right(5.seconds))
    assertEquals(GoDuration.parse("-1m"), Right(1.minute * -1))
    assertEquals(GoDuration.parse("+1m"), Right(1.minute))
    assertEquals(GoDuration.parse("0"), Right(Duration.Zero))

  test("truncates each term's fractional nanoseconds, as Go does"):
    assertEquals(GoDuration.parse("0.5ns0.5ns"), Right(Duration.Zero))
    assertEquals(GoDuration.parse("1.9ns"), Right(1.nano))

  test("rejects what Go's ParseDuration rejects"):
    List(
      "",
      "soon",
      "10",
      "1x",
      "s",
      ".s",
      ".",
      "1s ",
      " 1s",
      "1h30",
      "1ss",
      "--1s",
      "1.2.3s"
    )
      .foreach(s => assert(GoDuration.parse(s).isLeft, s"expected a Left for '$s'"))

  test("rejects durations beyond Go's range"):
    assert(GoDuration.parse("9223372036854775808ns").isLeft)
    assert(GoDuration.parse("9223372036854775807ns").isRight)

  test("renders like Go's Duration.String()"):
    assertEquals(GoDuration.render(5.seconds), "5s")
    assertEquals(GoDuration.render(90.seconds), "1m30s")
    assertEquals(GoDuration.render(500.millis), "500ms")
    assertEquals(GoDuration.render(1.hour), "1h0m0s")
    assertEquals(GoDuration.render(2.minutes), "2m0s")
    assertEquals(GoDuration.render(Duration.Zero), "0s")
    assertEquals(GoDuration.render(1500.millis), "1.5s")
    assertEquals(GoDuration.render(1500.micros), "1.5ms")
    assertEquals(GoDuration.render(7.micros), "7µs")
    assertEquals(GoDuration.render(7.nanos), "7ns")
    assertEquals(GoDuration.render(90.seconds * -1), "-1m30s")

  test("render(parse(x)) round-trips for the durations check settings use"):
    List("15s", "1m30s", "500ms", "10s").foreach: s =>
      assertEquals(GoDuration.parse(s).map(GoDuration.render), Right(s))

  property("parse(render(d)) == d"):
    val bound = Gen.oneOf(1000L, 1000000L, 1000000000L, Long.MaxValue)
    val nanosGen = bound.flatMap(b => Gen.choose(-b, b))
    forAll(nanosGen): nanos =>
      val d = FiniteDuration(nanos, NANOSECONDS)
      GoDuration.parse(GoDuration.render(d)) == Right(d)
end GoDurationSuite
