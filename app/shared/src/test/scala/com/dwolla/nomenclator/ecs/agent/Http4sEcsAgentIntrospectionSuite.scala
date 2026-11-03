package com.dwolla.nomenclator.ecs.agent

import cats.data.Kleisli
import cats.effect.*
import com.dwolla.nomenclator.ecs.*
import com.dwolla.nomenclator.model.*
import munit.CatsEffectSuite
import org.http4s.{Protocol as _, *}
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.syntax.all.*

import scala.io.Source
import scala.util.Using

class Http4sEcsAgentIntrospectionSuite extends CatsEffectSuite:
  private val base = uri"http://agent.invalid:51678"

  private def fixture(name: String): String =
    Using.resource(Source.fromResource(s"fixtures/ecs-agent/$name"))(
      _.mkString
    )

  private def json(body: String, status: Status = Status.Ok) =
    Response[IO](status)
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

  /** A client that answers every request with `response`, remembering the
    * request URIs.
    */
  private def stubClient(response: Response[IO]) =
    Ref
      .of[IO, List[Uri]](Nil)
      .map: seen =>
        seen -> Client.fromHttpApp[IO](
          Kleisli((req: Request[IO]) => seen.update(req.uri :: _).as(response))
        )

  private def port[A](from: Int => Either[String, A], n: Int): A =
    from(n).fold(fail(_), identity)

  private def binding(containerPort: Int, hostPort: Int, p: Protocol) =
    PortBinding(
      port(ContainerPort.from, containerPort),
      port(HostPort.from, hostPort),
      p
    )

  private val expectedTasks = List(
    EcsTask(
      "arn:aws:ecs:us-east-1:123456789012:task/example/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      TaskDefinitionRef("foo-service", 3),
      TaskStatus.Running,
      TaskStatus.Running,
      List(
        EcsContainer(
          ContainerName("web"),
          DockerName("ecs-foo-service-3-web-a1b2c3d4e5f6a7b8c9d0"),
          ImageName(
            "123456789012.dkr.ecr.us-east-1.amazonaws.com/foo-service:1.2.3"
          ),
          List(binding(8080, 32768, Protocol.Tcp))
        )
      )
    ),
    EcsTask(
      "arn:aws:ecs:us-east-1:123456789012:task/example/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      TaskDefinitionRef("bar-worker", 12),
      TaskStatus.Running,
      TaskStatus.Running,
      List(
        EcsContainer(
          ContainerName("worker"),
          DockerName("ecs-bar-worker-12-worker-0a1b2c3d4e5f6a7b8c9d"),
          ImageName(
            "123456789012.dkr.ecr.us-east-1.amazonaws.com/bar-worker:4.5.6"
          ),
          List(
            binding(9000, 32770, Protocol.Tcp),
            binding(9001, 32771, Protocol.Udp)
          )
        ),
        EcsContainer(
          ContainerName("sidecar"),
          DockerName("ecs-bar-worker-12-sidecar-1a2b3c4d5e6f7a8b9c0d"),
          ImageName(
            "123456789012.dkr.ecr.us-east-1.amazonaws.com/bar-sidecar:latest"
          ),
          Nil
        )
      )
    ),
    EcsTask(
      "arn:aws:ecs:us-east-1:123456789012:task/example/cccccccccccccccccccccccccccccccc",
      TaskDefinitionRef("foo-service", 2),
      TaskStatus.Running,
      TaskStatus.Stopped,
      List(
        EcsContainer(
          ContainerName("web"),
          DockerName("ecs-foo-service-2-web-b1c2d3e4f5a6b7c8d9e0"),
          ImageName(
            "123456789012.dkr.ecr.us-east-1.amazonaws.com/foo-service:1.2.2"
          ),
          List(binding(8080, 32769, Protocol.Tcp))
        )
      )
    ),
    EcsTask(
      "arn:aws:ecs:us-east-1:123456789012:task/example/dddddddddddddddddddddddddddddddd",
      TaskDefinitionRef("baz-api", 7),
      TaskStatus.Running,
      TaskStatus.Running,
      List(
        EcsContainer(
          ContainerName("api"),
          DockerName("ecs-baz-api-7-api-c1d2e3f4a5b6c7d8e9f0"),
          ImageName(
            "123456789012.dkr.ecr.us-east-1.amazonaws.com/baz-api:7.0.0"
          ),
          Nil
        )
      )
    )
  )

  test(
    "tasks.json decodes, with the duplicate binding collapsed and the awsvpc container's bindings dropped"
  ):
    stubClient(json(fixture("tasks.json"))).flatMap:
      case (seen, client) =>
        Http4sEcsAgentIntrospection(client, base).tasks.flatMap: tasks =>
          assertEquals(tasks, expectedTasks)
          seen.get.map(uris => assertEquals(uris.map(_.path.renderString), List("/v1/tasks")))

  test("Family and Version become the task definition reference"):
    stubClient(json(fixture("tasks.json"))).flatMap:
      case (_, client) =>
        Http4sEcsAgentIntrospection(client, base).tasks.map(tasks =>
          assertEquals(
            tasks.map(_.definition),
            List(
              TaskDefinitionRef("foo-service", 3),
              TaskDefinitionRef("bar-worker", 12),
              TaskDefinitionRef("foo-service", 2),
              TaskDefinitionRef("baz-api", 7)
            )
          )
        )

  test("metadata.json decodes the cluster and container instance ARN"):
    stubClient(json(fixture("metadata.json"))).flatMap:
      case (seen, client) =>
        Http4sEcsAgentIntrospection(client, base).metadata.flatMap: m =>
          assertEquals(
            m,
            InstanceMetadata(
              "example",
              "arn:aws:ecs:us-east-1:123456789012:container-instance/example/0123456789abcdef0123456789abcdef"
            )
          )
          seen.get.map(uris => assertEquals(uris.map(_.path.renderString), List("/v1/metadata")))

  test("a non-2xx tasks answer raises an error naming the path and status"):
    stubClient(Response[IO](Status.InternalServerError)).flatMap:
      case (_, client) =>
        Http4sEcsAgentIntrospection(client, base).tasks.attempt.map:
          case Left(e: EcsAgentError) =>
            assertEquals(e, EcsAgentError("GET", "/v1/tasks", 500))
            assert(e.getMessage.contains("/v1/tasks"), e.getMessage)
          case other => fail(s"expected an EcsAgentError, got $other")

  test("a non-2xx metadata answer raises an error naming the path and status"):
    stubClient(Response[IO](Status.NotFound)).flatMap:
      case (_, client) =>
        Http4sEcsAgentIntrospection(client, base).metadata.attempt.map:
          case Left(e: EcsAgentError) =>
            assertEquals(e, EcsAgentError("GET", "/v1/metadata", 404))
          case other => fail(s"expected an EcsAgentError, got $other")

  test("malformed task JSON fails instead of returning an empty list"):
    stubClient(json("""{"Tasks": [ {"Arn": """)).flatMap:
      case (_, client) =>
        Http4sEcsAgentIntrospection(client, base).tasks.attempt.map(result =>
          assert(result.isLeft, s"expected a failure, got $result")
        )

  test("a task that can't be decoded fails the whole list"):
    val body = fixture("tasks.json").replace(
      "\"Version\": \"3\"",
      "\"Version\": \"three\""
    )
    stubClient(json(body)).flatMap:
      case (_, client) =>
        Http4sEcsAgentIntrospection(client, base).tasks.attempt.map(result =>
          assert(result.isLeft, s"expected a failure, got $result")
        )
end Http4sEcsAgentIntrospectionSuite
