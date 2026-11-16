package com.dwolla.nomenclator.integration

import cats.effect.*
import com.dimafeng.testcontainers.GenericContainer

/** Publishes a test container's port on the host's loopback address only.
  *
  * Docker Desktop hands out random host ports in sequence and publishes them on
  * every address, beside any process that already listens on the same port on
  * 127.0.0.1 (as IDEs and local servers do). Connections to localhost then
  * reach that process instead of the container. Published on 127.0.0.1, the
  * container's port collides with such a listener and its start fails.
  */
object LoopbackPorts:
  private val startsOnRandomPorts = 3

  /** Starts the container that `create` makes, with `containerPort` published
    * on `hostPort` if given, and on a random host port otherwise. A random port
    * that collides is retried with a new container, which Docker gives the next
    * port. Any other start failure, and any collision on a chosen port, fails
    * at once with its cause.
    */
  def started[C <: GenericContainer](containerPort: Int, hostPort: Option[Int])(create: => C): Resource[IO, C] =
    def start(remaining: Int): IO[C] =
      IO.blocking:
        val c = create
        publish(c, containerPort, hostPort)
        c
      .flatMap: c =>
          IO.blocking:
            c.start()
            c
          .onError(_ => IO.blocking(c.stop()).attempt.void)
        .recoverWith:
          case e if remaining > 1 && portCollision(e) => start(remaining - 1)
    Resource.make(start(if (hostPort.isEmpty) startsOnRandomPorts else 1))(c => IO.blocking(c.stop()))

  /** Whether a container's start failed because its host port was taken.
    */
  def portCollision(failure: Throwable): Boolean =
    Iterator
      .iterate(failure)(_.getCause)
      .takeWhile(_ != null)
      .flatMap(e => Option(e.getMessage))
      .exists(_.contains("address already in use"))

  private def publish(container: GenericContainer, containerPort: Int, hostPort: Option[Int]): Unit =
    container.configure(
      _.setPortBindings(
        java.util.List.of(
          s"127.0.0.1:${hostPort.fold("")(_.toString)}:$containerPort"
        )
      )
    )
end LoopbackPorts
