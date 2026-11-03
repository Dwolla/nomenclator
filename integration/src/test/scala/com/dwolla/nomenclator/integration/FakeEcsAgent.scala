package com.dwolla.nomenclator.integration

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.Json
import io.circe.jawn
import org.http4s.*
import org.http4s.circe.*
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.noop.NoOpLogger

import scala.io.Source
import scala.util.Using

/** The ECS agent's introspection API on a random local port, serving the
  * `/v1/tasks` and `/v1/metadata` fixtures. A test can change the task list
  * between passes.
  */
final class FakeEcsAgent private (val base: Uri, tasks: Ref[IO, Json]):

  /** Drops the task with this ARN from what `/v1/tasks` answers.
    */
  def removeTask(arn: String): IO[Unit] =
    tasks.update(json =>
      json.hcursor
        .downField("Tasks")
        .withFocus(
          _.mapArray(_.filterNot(_.hcursor.get[String]("Arn").contains(arn)))
        )
        .top
        .getOrElse(json)
    )
end FakeEcsAgent

object FakeEcsAgent:
  private def fixture(name: String): IO[Json] =
    IO.blocking(
      Using.resource(Source.fromResource(s"fixtures/ecs-agent/$name"))(
        _.mkString
      )
    ).flatMap(jawn.parse(_).liftTo[IO])

  private def routes(tasks: Ref[IO, Json], metadata: Ref[IO, Json]) =
    HttpApp[IO]: req =>
      (req.method, req.uri.path.renderString) match
        case (Method.GET, "/v1/tasks") =>
          tasks.get.map(Response[IO]().withEntity(_))
        case (Method.GET, "/v1/metadata") =>
          metadata.get.map(Response[IO]().withEntity(_))
        case _ => Response[IO](Status.NotFound).pure[IO]

  val resource: Resource[IO, FakeEcsAgent] =
    for
      tasks <- fixture("tasks.json").flatMap(Ref.of[IO, Json]).toResource
      metadata <- fixture("metadata.json").flatMap(Ref.of[IO, Json]).toResource
      server <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withLogger(NoOpLogger[IO])
        .withHttpApp(routes(tasks, metadata))
        .build
    yield new FakeEcsAgent(server.baseUri, tasks)
end FakeEcsAgent
