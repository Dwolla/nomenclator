package com.dwolla.nomenclator.ecs.api

import cats.effect.*
import cats.syntax.all.*
import com.amazonaws.ecs.{ECS, ECSGen}
import fs2.compression.Compression
import org.http4s.client.Client
import smithy4s.Timestamp
import smithy4s.aws.{AwsClient, AwsCredentials, AwsEnvironment, AwsRegion}

object EcsClient:

  /** An ECS client that signs with `credentials`. smithy4s's default
    * credentials chain is never consulted.
    *
    * `Async` is required only because smithy4s's `AwsClient` demands it; the
    * rest of Nomenclator asks for less.
    */
  def resource[F[_]: Async](client: Client[F], region: AwsRegion, credentials: F[AwsCredentials]): Resource[F, ECS[F]] =
    AwsClient[ECSGen, F](
      ECS,
      AwsEnvironment.make(
        client,
        region.pure[F],
        credentials,
        Clock[F].realTime.map(now => Timestamp(now.toSeconds, 0))
      )
    )(using summon, Compression.forSync[F])
end EcsClient
