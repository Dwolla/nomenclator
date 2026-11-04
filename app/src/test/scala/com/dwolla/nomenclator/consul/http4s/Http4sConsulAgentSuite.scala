package com.dwolla.nomenclator.consul.http4s

import cats.data.Kleisli
import cats.effect.*
import com.dwolla.nomenclator.consul.algebra.ConsulAgentError
import com.dwolla.nomenclator.consul.model.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.syntax.all.*
import org.typelevel.ci.*

class Http4sConsulAgentSuite extends CatsEffectSuite:
  private val token = "s3cret-token-value"
  private val tokenHeader = ci"X-Consul-Token"
  private val base = uri"http://consul.invalid:8500"
  private val id = ServiceId("h:web:80")

  /** A client that answers every request with `response` and remembers the
    * requests it received.
    */
  private def stubClient(response: Response[IO]) =
    Ref
      .of[IO, List[Request[IO]]](Nil)
      .map: seen =>
        seen -> Client.fromHttpApp[IO](
          Kleisli((req: Request[IO]) => seen.update(req :: _).as(response))
        )

  test("a forbidden answer's error never contains the token"):
    stubClient(
      Response[IO](Status.Forbidden).withEntity(s"denied for token $token")
    ).flatMap:
      case (_, client) =>
        Http4sConsulAgent(client, base, Some(token))
          .deregister(id)
          .attempt
          .map:
            case Left(e: ConsulAgentError) =>
              assertEquals(e.status, 403)
              assert(!e.getMessage.contains(token), e.getMessage)
              assert(!e.bodyExcerpt.contains(token), e.bodyExcerpt)
              assert(e.bodyExcerpt.contains("denied for token"))
            case other => fail(s"expected a ConsulAgentError, got $other")

  test("the token header is sent when a token is configured"):
    stubClient(Response[IO](Status.Ok)).flatMap:
      case (seen, client) =>
        Http4sConsulAgent(client, base, Some(token)).deregister(id) >>
          seen.get.map(requests =>
            assertEquals(
              requests.flatMap(_.headers.get(tokenHeader)).map(_.head.value),
              List(token)
            )
          )

  test("the token header is absent when no token is configured"):
    stubClient(Response[IO](Status.Ok)).flatMap:
      case (seen, client) =>
        Http4sConsulAgent(client, base, None).deregister(id) >>
          seen.get.map(requests => assertEquals(requests.flatMap(_.headers.get(tokenHeader)), Nil))

  test("a decoding failure never carries the response body"):
    val secret = "s3cret-service-name"
    stubClient(
      Response[IO](Status.Ok)
        .withEntity(s"""{"h:web:80":{"ID":"h:web:80","Service":["$secret"]}}""")
        .withContentType(`Content-Type`(MediaType.application.json))
    ).flatMap:
      case (_, client) =>
        Http4sConsulAgent(client, base, None).state.attempt.map:
          case Left(e) =>
            val chain = List.unfold(Option(e))(
              _.map(t => (String.valueOf(t.getMessage), Option(t.getCause)))
            )
            assert(!chain.exists(_.contains(secret)), chain)
          case Right(state) => fail(s"expected a failure, got $state")

  private val emptyObject =
    Response[IO](Status.Ok)
      .withEntity("{}")
      .withContentType(`Content-Type`(MediaType.application.json))

  // An ACL token that can't read every service gets the rest silently
  // dropped; the state would look like services had gone.
  test("a read whose results ACLs filtered fails"):
    stubClient(
      emptyObject.putHeaders(
        Header.Raw(ci"X-Consul-Results-Filtered-By-ACLs", "true")
      )
    ).flatMap:
      case (_, client) =>
        Http4sConsulAgent(client, base, Some(token)).state.attempt.map:
          case Left(e: ConsulResultsFilteredError) =>
            assertEquals(e.path, "/v1/agent/services")
            assert(!e.getMessage.contains(token), e.getMessage)
          case other =>
            fail(s"expected a ConsulResultsFilteredError, got $other")

  test("a read whose results ACLs did not filter succeeds"):
    stubClient(
      emptyObject.putHeaders(
        Header.Raw(ci"X-Consul-Results-Filtered-By-ACLs", "false")
      )
    ).flatMap:
      case (_, client) =>
        Http4sConsulAgent(client, base, Some(token)).state.map(state => assertEquals(state.services, Nil))

  test("deregistering an ID the agent doesn't know succeeds"):
    stubClient(Response[IO](Status.NotFound)).flatMap:
      case (_, client) =>
        Http4sConsulAgent(client, base, None).deregister(id)
end Http4sConsulAgentSuite
