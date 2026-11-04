package com.dwolla.nomenclator.ecs.smithy4s

import cats.data.Kleisli
import cats.effect.*
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.syntax.all.*
import org.typelevel.ci.*
import smithy4s.aws.{AwsCredentials, AwsRegion}

class EcsClientSuite extends CatsEffectSuite:
  private val credentials: AwsCredentials =
    AwsCredentials.Default("AKIDEXAMPLE", "secret-example", None)

  /** Describes one task definition through a client that answers `{}`, and
    * returns the requests it sent.
    */
  private def describeThrough(endpoint: Option[Uri]): IO[List[Request[IO]]] =
    for
      seen <- Ref.of[IO, List[Request[IO]]](Nil)
      client = Client.fromHttpApp[IO](
        Kleisli((req: Request[IO]) =>
          seen
            .update(req :: _)
            .as(
              Response[IO](Status.Ok)
                .withEntity("{}")
                .withContentType(
                  `Content-Type`(
                    MediaType.unsafeParse("application/x-amz-json-1.1")
                  )
                )
            )
        )
      )
      _ <- EcsClient
        .resource[IO](
          client,
          AwsRegion.US_EAST_1,
          credentials.pure[IO],
          endpoint
        )
        .use(_.describeTaskDefinition("foo-service:7"))
      requests <- seen.get
    yield requests

  test("sends requests to the regional ECS endpoint"):
    describeThrough(None).map(requests =>
      assertEquals(
        requests.map(_.uri.authority.map(_.renderString)),
        List(Some("ecs.us-east-1.amazonaws.com"))
      )
    )

  test("sends requests to the endpoint override, still signed for the region"):
    describeThrough(Some(uri"http://127.0.0.1:4566")).map: requests =>
      assertEquals(
        requests.map(r => (r.uri.scheme, r.uri.authority.map(_.renderString))),
        List((Some(Uri.Scheme.http), Some("127.0.0.1:4566")))
      )
      val signed = requests.headOption
        .flatMap(_.headers.get(ci"Authorization"))
        .map(_.head.value)
        .getOrElse("")
      assert(signed.contains("/us-east-1/ecs/aws4_request"), signed)

  test("signs requests with the supplied credentials and region"):
    describeThrough(None).map: requests =>
      val authorization = requests.headOption
        .flatMap(_.headers.get(ci"Authorization"))
        .map(_.head.value)
        .getOrElse("")
      assert(
        authorization.contains("Credential=AKIDEXAMPLE/") &&
          authorization.contains("/us-east-1/ecs/aws4_request"),
        authorization
      )
end EcsClientSuite
