package com.dwolla.nomenclator.consul.http4s

import cats.effect.*
import cats.syntax.all.*
import com.dwolla.nomenclator.consul.algebra.{ConsulAgent, ConsulAgentError}
import com.dwolla.nomenclator.consul.model.*
import com.dwolla.nomenclator.http.RedactedJson
import com.dwolla.nomenclator.registration.Registration
import io.circe.Decoder
import org.http4s.*
import org.http4s.circe.jsonEncoder
import org.http4s.client.Client
import org.typelevel.ci.*

object Http4sConsulAgent:
  private val tokenHeader = ci"X-Consul-Token"
  private val filteredHeader = ci"X-Consul-Results-Filtered-By-ACLs"
  private val excerptLength = 200

  /** @param token
    *   sent as `X-Consul-Token` on every request when present
    */
  def apply[F[_]: Concurrent](client: Client[F], base: Uri, token: Option[String]): ConsulAgent[F] = new ConsulAgent[F]:
    private def request(method: Method, path: String*): Request[F] =
      Request[F](method, path.foldLeft(base / "v1" / "agent")(_ / _))
        .putHeaders(token.map(t => Header.Raw(tokenHeader, t)).toList)

    private def send[A](req: Request[F])(read: Response[F] => F[A]): F[A] =
      client
        .run(req)
        .use: response =>
          if (response.status.isSuccess) read(response)
          else
            response.bodyText.compile.string.flatMap: body =>
              ConsulAgentError(
                req.method.name,
                req.uri.path.renderString,
                response.status.code,
                excerpt(body)
              ).raiseError[F, A]

    // The token is never in what Consul says back, but redacting it makes
    // that a guarantee instead of an assumption.
    private def excerpt(body: String): String =
      token
        .filter(_.nonEmpty)
        .fold(body)(body.replace(_, "<redacted>"))
        .take(excerptLength)

    /** Fails if the agent says ACLs filtered the results, as Consul 1.11 and
      * later do: a partial answer must never look like a complete one, or the
      * services left out would be deregistered.
      */
    private def get[A](path: String*)(using Decoder[A]): F[A] =
      val req = request(Method.GET, path*)
      send(req): response =>
        if (filtered(response))
          response.body.compile.drain >>
            ConsulResultsFilteredError(req.uri.path.renderString)
              .raiseError[F, A]
        else response.as[A](using summon, RedactedJson.decoder[F, A])

    private def filtered(response: Response[F]): Boolean =
      response.headers
        .get(filteredHeader)
        .exists(_.exists(_.value.equalsIgnoreCase("true")))

    override val advertisedAddress: F[ServiceAddress] =
      get[ServiceAddress]("self")(using ConsulCodecs.memberAddress)

    // Two reads, not an atomic snapshot: acceptable while the reconciler is
    // the only writer.
    override val state: F[AgentState] =
      (
        get[List[AgentService]]("services")(using
          ConsulCodecs.agentServices
        ),
        get[List[AgentCheck]]("checks")(using
          ConsulCodecs.agentChecks
        )
      ).mapN(AgentState.apply)

    override def register(r: Registration): F[Unit] =
      send(
        request(Method.PUT, "service", "register")
          .withEntity(ConsulCodecs.registerBody(r))
      )(_.body.compile.drain)

    // Idempotent: newer agents answer 404 for an unknown ID, and a service can
    // vanish between reading state and deregistering it.
    override def deregister(id: ServiceId): F[Unit] =
      send(request(Method.PUT, "service", "deregister", id.value))(
        _.body.compile.drain
      ).recover:
        case ConsulAgentError(_, _, 404, _) => ()
end Http4sConsulAgent
