package com.dwolla.nomenclator.ecs.http4s

import cats.effect.*
import cats.syntax.all.*
import com.dwolla.nomenclator.ecs.algebra.{EcsAgentError, EcsAgentIntrospection}
import com.dwolla.nomenclator.ecs.model.{EcsTask, InstanceMetadata}
import com.dwolla.nomenclator.http.RedactedJson
import io.circe.Decoder
import org.http4s.*
import org.http4s.client.Client
import org.http4s.syntax.all.*

object Http4sEcsAgentIntrospection:

  /** Where the agent listens when Nomenclator runs in host network mode.
    */
  val defaultBase: Uri = uri"http://localhost:51678"

  def apply[F[_]: Concurrent](client: Client[F], base: Uri): EcsAgentIntrospection[F] = new EcsAgentIntrospection[F]:
    private def get[A](decoder: Decoder[A], path: String): F[A] =
      val req = Request[F](Method.GET, base / "v1" / path)
      client
        .run(req)
        .use: response =>
          if (response.status.isSuccess)
            response
              .as[A](using summon, RedactedJson.decoder(using summon, decoder))
          else
            response.body.compile.drain >>
              EcsAgentError(
                req.method.name,
                req.uri.path.renderString,
                response.status.code
              ).raiseError[F, A]

    override val metadata: F[InstanceMetadata] =
      get(IntrospectionCodecs.metadata, "metadata")

    override val tasks: F[List[EcsTask]] =
      get(IntrospectionCodecs.tasks, "tasks")
end Http4sEcsAgentIntrospection
