package com.dwolla.nomenclator.ecs.api

import cats.effect.*
import cats.syntax.all.*
import com.amazonaws.ecs.{ECS, ECSGen}
import fs2.compression.Compression
import org.http4s.Uri
import org.http4s.client.Client
import smithy4s.aws.{AwsClient, AwsCredentials, AwsEnvironment, AwsRegion}
import smithy4s.{Endpoint, Hints, Timestamp}

object EcsClient:

  /** An ECS client that signs with `credentials`. smithy4s's default
    * credentials chain is never consulted.
    *
    * `Async` is required only because smithy4s's `AwsClient` demands it; the
    * rest of Nomenclator asks for less.
    *
    * @param endpoint
    *   where to send requests instead of the regional endpoint: its scheme and
    *   authority replace the regional ones before the request is signed
    */
  def resource[F[_]: Async]
    (client: Client[F], region: AwsRegion, credentials: F[AwsCredentials], endpoint: Option[Uri]): Resource[F, ECS[F]] =
    val environment = AwsEnvironment.make(
      client,
      region.pure[F],
      credentials,
      Clock[F].realTime.map(now => Timestamp(now.toSeconds, 0))
    )
    AwsClient[ECSGen, F](
      ECS,
      endpoint.fold(environment)(e => environment.withMiddleware(sendingTo(e)))
    )(using summon, Compression.forSync[F])

  // smithy4s applies its endpoint middleware outside its signing middleware,
  // so the request is signed for the host it is sent to.
  private def sendingTo[F[_]: MonadCancelThrow](endpoint: Uri): Endpoint.Middleware[Client[F]] =
    new Endpoint.Middleware.Simple[Client[F]]:
      override def prepareWithHints(serviceHints: Hints, endpointHints: Hints): Client[F] => Client[F] = client =>
        Client[F](request =>
          client.run(
            request.withUri(
              request.uri.copy(
                scheme = endpoint.scheme,
                authority = endpoint.authority
              )
            )
          )
        )
end EcsClient
