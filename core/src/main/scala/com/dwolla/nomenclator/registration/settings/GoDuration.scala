package com.dwolla.nomenclator.registration.settings

import cats.syntax.all.*

import scala.concurrent.duration.*

/** Go's `time.ParseDuration` syntax and `Duration.String()` rendering.
  * Registrator passes `check_interval` and `check_timeout` to Consul as
  * strings, and Consul parses them as Go durations.
  */
object GoDuration:
  private val nanosPerUnit: Map[String, BigDecimal] = Map(
    "ns" -> BigDecimal(1),
    "us" -> BigDecimal(1000),
    "µs" -> BigDecimal(1000), // U+00B5 micro sign
    "μs" -> BigDecimal(1000), // U+03BC Greek mu
    "ms" -> BigDecimal(1000000),
    "s" -> BigDecimal(1000000000L),
    "m" -> BigDecimal(60000000000L),
    "h" -> BigDecimal(3600000000000L)
  )
  private val Term = "([0-9]*)(?:\\.([0-9]*))?(ns|us|µs|μs|ms|s|m|h)".r
  private val maxNanos = BigDecimal(Long.MaxValue)

  def parse(s: String): Either[String, FiniteDuration] =
    val (negative, unsigned) = s.headOption match
      case Some('-') => (true, s.drop(1))
      case Some('+') => (false, s.drop(1))
      case _         => (false, s)
    if (unsigned == "0") Duration.Zero.asRight
    else if (unsigned.isEmpty) s"invalid duration '$s'".asLeft
    else
      terms(unsigned, s).flatMap: nanos =>
        val signed = if (negative) -nanos else nanos
        if (signed.abs > maxNanos) s"duration '$s' is out of range".asLeft
        else FiniteDuration(signed.toBigInt.toLong, NANOSECONDS).asRight

  private def terms(remaining: String, original: String): Either[String, BigDecimal] =
    if (remaining.isEmpty) BigDecimal(0).asRight
    else
      Term.findPrefixMatchOf(remaining) match
        case Some(m) if m.group(1).nonEmpty || Option(m.group(2)).exists(_.nonEmpty) =>
          val number = BigDecimal(
            s"${orZero(m.group(1))}.${orZero(m.group(2))}"
          )
          terms(remaining.drop(m.end), original)
            .map(_ + BigDecimal((number * nanosPerUnit(m.group(3))).toBigInt))
        case _ => s"invalid duration '$original'".asLeft

  private def orZero(digits: String): String =
    Option(digits).filter(_.nonEmpty).getOrElse("0")

  /** Matches Go's `Duration.String()`: `5s`, `1m30s`, `500ms`, `1h0m0s`.
    */
  def render(d: FiniteDuration): String =
    val nanos = d.toNanos
    val sign = if (nanos < 0) "-" else ""
    val magnitude = nanos.abs
    val body =
      if (magnitude == 0) "0s"
      else if (magnitude < 1000L) s"${magnitude}ns"
      else if (magnitude < 1000000L) s"${fraction(magnitude, 1000L)}µs"
      else if (magnitude < 1000000000L) s"${fraction(magnitude, 1000000L)}ms"
      else wholeSeconds(magnitude)
    sign + body

  private def wholeSeconds(magnitude: Long): String =
    val seconds = fraction(magnitude % 60000000000L, 1000000000L)
    val totalMinutes = magnitude / 60000000000L
    if (totalMinutes == 0) s"${seconds}s"
    else if (totalMinutes < 60) s"${totalMinutes}m${seconds}s"
    else s"${totalMinutes / 60}h${totalMinutes % 60}m${seconds}s"

  /** `value / unit` in decimal, without trailing zeros in the fraction.
    */
  private def fraction(value: Long, unit: Long): String =
    val remainder = value % unit
    if (remainder == 0) (value / unit).toString
    else {
      val digits = unit.toString.length - 1
      val padded = remainder.toString.reverse.padTo(digits, '0').reverse
      s"${value / unit}.${padded.reverse.dropWhile(_ == '0').reverse}"
    }
end GoDuration
