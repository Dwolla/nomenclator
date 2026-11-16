package com.dwolla.nomenclator.integration

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.Json
import io.circe.literal.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.*
import org.typelevel.log4cats.noop.NoOpLogger

import java.time.Instant
import scala.concurrent.duration.*

/** The AWS endpoints Nomenclator calls, on a random local port: a task-role
  * credentials endpoint handing out made-up credentials, and the ECS API's
  * `DescribeTaskDefinition`, which knows the task definitions of the
  * introspection fixtures. Nothing checks signatures.
  */
final class FakeAws private (val base: Uri):

  /** For `AWS_CONTAINER_CREDENTIALS_FULL_URI`.
    */
  val credentials: Uri = base / "credentials"
end FakeAws

object FakeAws:
  private val describeTaskDefinition =
    "AmazonEC2ContainerServiceV20141113.DescribeTaskDefinition"
  private val awsJson = MediaType.unsafeParse("application/x-amz-json-1.1")

  private def environment(settings: (String, String)*): Json =
    Json.fromValues(
      settings.map((name, value) => json"""{"name": $name, "value": $value}""")
    )

  /** The fixtures' task definitions, with their `SERVICE_*` settings. ECS
    * always sends each container's `cpu`, and smithy4s requires it: the model
    * gives it a default.
    */
  private val definitions: Map[String, Json] = Map(
    "foo-service:3" -> json"""{
      "family": "foo-service",
      "revision": 3,
      "containerDefinitions": [{
        "name": "web",
        "cpu": 0,
        "environment": ${environment(
        "SERVICE_TAGS" -> "web",
        "SERVICE_CHECK_TCP" -> "true"
      )}
      }]
    }""",
    "bar-worker:12" -> json"""{
      "family": "bar-worker",
      "revision": 12,
      "containerDefinitions": [{ "name": "worker", "cpu": 0, "environment": [] }]
    }"""
  )

  private def credentials: IO[Response[IO]] =
    IO.realTimeInstant.map: now =>
      val expiration: Instant = now.plusSeconds(1.hour.toSeconds)
      Response[IO]().withEntity(
        json"""{
          "AccessKeyId": "AKIDEXAMPLE",
          "SecretAccessKey": "not-a-real-secret",
          "Token": "not-a-real-token",
          "Expiration": ${expiration.toString}
        }"""
      )

  private def describe(req: Request[IO]): IO[Response[IO]] =
    req
      .as[Json]
      .map: body =>
        body.hcursor
          .get[String]("taskDefinition")
          .toOption
          .flatMap(definitions.get) match
          case Some(definition) =>
            Response[IO]()
              .withEntity(json"""{"taskDefinition": $definition}""")
              .withContentType(`Content-Type`(awsJson))
          case None =>
            Response[IO](Status.BadRequest)
              .withEntity(
                json"""{"__type": "ClientException", "message": "Unable to describe task definition."}"""
              )
              .withContentType(`Content-Type`(awsJson))

  private val routes = HttpApp[IO]: req =>
    (req.method, req.uri.path.renderString) match
      case (Method.GET, "/credentials") => credentials
      case (Method.POST, "/")
          if req.headers
            .get(ci"X-Amz-Target")
            .exists(_.head.value == describeTaskDefinition) =>
        describe(req)
      case _ => Response[IO](Status.NotFound).pure[IO]

  val resource: Resource[IO, FakeAws] =
    EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"0")
      .withLogger(NoOpLogger[IO])
      .withHttpApp(routes)
      .build
      .map(server => new FakeAws(server.baseUri))
end FakeAws
