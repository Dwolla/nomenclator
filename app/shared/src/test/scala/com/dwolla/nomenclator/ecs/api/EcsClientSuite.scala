package com.dwolla.nomenclator.ecs.api

import cats.data.Kleisli
import cats.effect.*
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.typelevel.ci.*
import org.http4s.headers.`Content-Type`
import smithy4s.aws.{AwsCredentials, AwsRegion}

class EcsClientSuite extends CatsEffectSuite:
  private val credentials: AwsCredentials =
    AwsCredentials.Default("AKIDEXAMPLE", "secret-example", None)

  test("signs requests with the supplied credentials and region"):
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
        .resource[IO](client, AwsRegion.US_EAST_1, credentials.pure[IO])
        .use(_.describeTaskDefinition("foo-service:7"))
      requests <- seen.get
    yield
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
