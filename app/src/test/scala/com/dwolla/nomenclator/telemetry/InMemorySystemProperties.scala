package com.dwolla.nomenclator.telemetry

import cats.effect.std.SystemProperties
import cats.effect.{IO, Ref}

/** System properties held in a `Ref`, so a test reads and sets its own and
  * never the JVM's.
  */
final class InMemorySystemProperties private (properties: Ref[IO, Map[String, String]]) extends SystemProperties[IO]:
  def get(key: String): IO[Option[String]] = properties.get.map(_.get(key))

  def set(key: String, value: String): IO[Option[String]] =
    properties.modify(current => (current.updated(key, value), current.get(key)))

  def clear(key: String): IO[Option[String]] =
    properties.modify(current => (current - key, current.get(key)))

  def all: IO[Map[String, String]] = properties.get
end InMemorySystemProperties

object InMemorySystemProperties:
  def of(initial: Map[String, String]): IO[InMemorySystemProperties] =
    Ref
      .of[IO, Map[String, String]](initial)
      .map(new InMemorySystemProperties(_))
