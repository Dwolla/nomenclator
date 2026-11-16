package com.dwolla.nomenclator.ecs.http4s

import cats.data.Kleisli
import cats.effect.*
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.syntax.all.*
import org.typelevel.log4cats.testing.StructuredTestingLogger

import java.time.Instant
import scala.concurrent.duration.*

class ContainerCredentialsSuite extends CatsEffectSuite:
  private val credentialsUri =
    uri"http://credentials.invalid/v2/credentials/example-id"

  private def secretFor(call: Int) = s"secret-example-$call"

  /** A credentials endpoint whose nth call (1-based) fails when
    * `failingCalls(n)`, and otherwise issues credentials numbered by call that
    * expire an hour from the (possibly simulated) current time.
    */
  private def endpoint(failingCalls: Set[Int], hangingCalls: Set[Int] = Set.empty, expiration: Option[String] = None) =
    (Ref.of[IO, Int](0), Ref.of[IO, List[Uri]](Nil)).mapN: (calls, uris) =>
      val app = Kleisli: (req: Request[IO]) =>
        for
          _ <- uris.update(req.uri :: _)
          n <- calls.updateAndGet(_ + 1)
          now <- IO.realTime
          expires = expiration.getOrElse(
            Instant.ofEpochMilli((now + 1.hour).toMillis).toString
          )
          _ <- IO.never.whenA(hangingCalls(n))
        yield
          if (failingCalls(n)) Response[IO](Status.InternalServerError)
          else
            Response[IO](Status.Ok)
              .withEntity(s"""{
                |  "AccessKeyId": "AKIDEXAMPLE$n",
                |  "SecretAccessKey": "${secretFor(n)}",
                |  "Token": "token-example-$n",
                |  "Expiration": "$expires"
                |}""".stripMargin)
              .withContentType(`Content-Type`(MediaType.application.json))
      (calls, uris, Client.fromHttpApp[IO](app))

  private def accessKey(credentials: IO[smithy4s.aws.AwsCredentials]) =
    credentials.map(_.accessKeyId)

  test("the task-role endpoint is the relative URI on ECS's credentials host"):
    assertEquals(
      ContainerCredentials.taskRoleEndpoint(path"/v2/credentials/example-id"),
      uri"http://169.254.170.2/v2/credentials/example-id"
    )

  test("the task-role endpoint stays on ECS's credentials host"):
    List("//evil.com/x", "/@evil.com/x", "/.evil.com/x").foreach: p =>
      assertEquals(
        ContainerCredentials
          .taskRoleEndpoint(Uri.Path.unsafeFromString(p))
          .authority,
        uri"http://169.254.170.2".authority,
        p
      )

  test("fetches from the credentials URI"):
    for
      (_, uris, client) <- endpoint(Set.empty)
      logger = StructuredTestingLogger.impl[IO]()
      key <- ContainerCredentials
        .resource[IO](client, credentialsUri, logger)
        .use(accessKey)
      seen <- uris.get
    yield
      assertEquals(key, "AKIDEXAMPLE1")
      assertEquals(
        seen,
        List(uri"http://credentials.invalid/v2/credentials/example-id")
      )

  test("acquisition fails when the first fetch fails"):
    for
      (_, _, client) <- endpoint(Set(1))
      logger = StructuredTestingLogger.impl[IO]()
      result <- ContainerCredentials
        .resource[IO](client, credentialsUri, logger)
        .use(_ => IO.unit)
        .attempt
    yield assert(result.isLeft)

  test("refreshes five minutes before expiration"):
    val program = for
      (calls, _, client) <- endpoint(Set.empty)
      logger = StructuredTestingLogger.impl[IO]()
      result <- ContainerCredentials
        .resource[IO](client, credentialsUri, logger)
        .use: credentials =>
          for
            before <- IO.sleep(55.minutes - 1.second) >> (
              accessKey(credentials),
              calls.get
            ).tupled
            after <- IO
              .sleep(2.seconds) >> (accessKey(credentials), calls.get).tupled
          yield (before, after)
    yield result

    TestControl
      .executeEmbed(program)
      .map:
        case (before, after) =>
          assertEquals(before, ("AKIDEXAMPLE1", 1))
          assertEquals(after, ("AKIDEXAMPLE2", 2))

  test(
    "keeps serving the old credentials through three failures, then recovers"
  ):
    // The refresh at 55 minutes fails (call 2), as do retries after 1 s (3)
    // and 2 s (4); the retry after 4 s (call 5) succeeds.
    val program = for
      (calls, _, client) <- endpoint(Set(2, 3, 4))
      logger = StructuredTestingLogger.impl[IO]()
      result <- ContainerCredentials
        .resource[IO](client, credentialsUri, logger)
        .use: credentials =>
          for
            failing <- IO.sleep(55.minutes + 6.seconds) >>
              (accessKey(credentials), calls.get).tupled
            recovered <- IO.sleep(2.seconds) >>
              (accessKey(credentials), calls.get).tupled
          yield (failing, recovered)
      logged <- logger.logged
    yield (result, logged)

    TestControl
      .executeEmbed(program)
      .map:
        case ((failing, recovered), logged) =>
          assertEquals(failing, ("AKIDEXAMPLE1", 4))
          assertEquals(recovered, ("AKIDEXAMPLE5", 5))
          assertEquals(
            logged.count(_.isInstanceOf[StructuredTestingLogger.WARN]),
            1,
            logged.toString
          )
          assertEquals(
            logged.count(_.isInstanceOf[StructuredTestingLogger.INFO]),
            1,
            logged.toString
          )

  test("backoff doubles from one second and caps at one minute"):
    // From the 55 minute refresh on, every call fails. Retries come 1, 2, 4, 8,
    // 16, 32, then every 60 seconds after it. Samples avoid the instants at
    // which a call is due, because ties with the test's own timer are unordered.
    val program = for
      (calls, _, client) <- endpoint(Set.from(2 to 1000))
      logger = StructuredTestingLogger.impl[IO]()
      result <- ContainerCredentials
        .resource[IO](client, credentialsUri, logger)
        .use: credentials =>
          for
            // Calls 2..7 fall at 0, 1, 3, 7, 15, and 31 seconds after the
            // refresh is due; call 8 is due at 63 seconds.
            early <- IO.sleep(55.minutes + 62.seconds) >> calls.get
            // Call 9 is due at 123 seconds, call 10 at 183.
            capped <- IO.sleep(63.seconds) >> calls.get
            key <- accessKey(credentials)
          yield (early, capped, key)
    yield result

    TestControl
      .executeEmbed(program)
      .map:
        case (early, capped, key) =>
          assertEquals(early, 7)
          assertEquals(capped, 9)
          assertEquals(key, "AKIDEXAMPLE1")

  private def causeChain(e: Throwable): List[Throwable] =
    List.unfold(Option(e))(_.map(t => t -> Option(t.getCause)))

  private def assertNoSecret(e: Throwable) =
    causeChain(e).foreach: t =>
      assert(!t.toString.contains("secret-example"), t.toString)
      assert(!t.getMessage.contains("secret-example"), t.getMessage)

  private def failedAcquisition(body: String) =
    val client = Client.fromHttpApp[IO](
      Kleisli((_: Request[IO]) =>
        IO.pure(
          Response[IO](Status.Ok)
            .withEntity(body)
            .withContentType(`Content-Type`(MediaType.application.json))
        )
      )
    )
    ContainerCredentials
      .resource[IO](
        client,
        credentialsUri,
        StructuredTestingLogger.impl[IO]()
      )
      .use(_ => IO.unit)
      .attempt
  end failedAcquisition

  test(
    "a body with a bad Expiration fails acquisition without echoing the secret"
  ):
    failedAcquisition(
      """{"AccessKeyId": "AKIDEXAMPLE", "SecretAccessKey": "secret-example", "Token": "t", "Expiration": "not-a-time"}"""
    ).map: result =>
      val e = result.left.getOrElse(fail("expected a failure"))
      assert(e.isInstanceOf[ContainerCredentialsError], e.toString)
      assertNoSecret(e)

  test("malformed JSON fails acquisition without echoing the secret"):
    failedAcquisition(
      """{"AccessKeyId": "AKIDEXAMPLE", "SecretAccessKey": "secret-example", """
    ).map: result =>
      val e = result.left.getOrElse(fail("expected a failure"))
      assert(e.isInstanceOf[ContainerCredentialsError], e.toString)
      assertNoSecret(e)

  test(
    "an absurdly distant Expiration still refreshes within the one hour clamp"
  ):
    val program = for
      (calls, _, client) <- endpoint(
        Set.empty,
        expiration = Some("9999-12-31T23:59:59Z")
      )
      logger = StructuredTestingLogger.impl[IO]()
      result <- ContainerCredentials
        .resource[IO](client, credentialsUri, logger)
        .use: _ =>
          for
            before <- IO.sleep(1.hour - 1.second) >> calls.get
            after <- IO.sleep(2.seconds) >> calls.get
          yield (before, after)
    yield result

    TestControl
      .executeEmbed(program)
      .map: result =>
        assertEquals(result, (1, 2))

  test("a hung endpoint times out after ten seconds and is retried"):
    // Call 2 (the 55 minute refresh) hangs; it times out at 10 s, and the
    // retry one second later is call 3.
    val program = for
      (calls, _, client) <- endpoint(Set.empty, hangingCalls = Set(2))
      logger = StructuredTestingLogger.impl[IO]()
      result <- ContainerCredentials
        .resource[IO](client, credentialsUri, logger)
        .use: credentials =>
          for
            hung <- IO.sleep(55.minutes + 5.seconds) >> accessKey(credentials)
            retried <- IO.sleep(7.seconds) >> accessKey(credentials)
            n <- calls.get
          yield (hung, retried, n)
    yield result

    TestControl
      .executeEmbed(program)
      .map: result =>
        assertEquals(result, ("AKIDEXAMPLE1", "AKIDEXAMPLE3", 3))

  test(
    "logs once when the served credentials expire and once when that clears"
  ):
    // Refreshing fails from 55 minutes on through call 15; call 16 succeeds
    // about nine minutes in, after the credentials expired at five.
    def errorsAndInfos(logger: StructuredTestingLogger[IO]) =
      logger.logged.map(l =>
        (
          l.count(_.isInstanceOf[StructuredTestingLogger.ERROR]),
          l.count(_.isInstanceOf[StructuredTestingLogger.INFO])
        )
      )
    val program = for
      (_, _, client) <- endpoint(Set.from(2 to 15))
      logger = StructuredTestingLogger.impl[IO]()
      result <- ContainerCredentials
        .resource[IO](client, credentialsUri, logger)
        .use: credentials =>
          for
            beforeExpiry <- IO.sleep(55.minutes + 4.minutes) >> errorsAndInfos(
              logger
            )
            stale <- IO.sleep(3.minutes) >> (
              errorsAndInfos(logger),
              accessKey(credentials)
            ).tupled
            cleared <- IO.sleep(4.minutes) >> (
              errorsAndInfos(logger),
              accessKey(credentials)
            ).tupled
          yield (beforeExpiry, stale, cleared)
    yield result

    TestControl
      .executeEmbed(program)
      .map:
        case (beforeExpiry, stale, cleared) =>
          assertEquals(beforeExpiry, (0, 0))
          assertEquals(stale, ((1, 0), "AKIDEXAMPLE1"))
          assertEquals(cleared, ((1, 2), "AKIDEXAMPLE16"))

  test("a continuing outage logs one warning, not one per retry"):
    val program = for
      (_, _, client) <- endpoint(Set.from(2 to 1000))
      logger = StructuredTestingLogger.impl[IO]()
      _ <- ContainerCredentials
        .resource[IO](client, credentialsUri, logger)
        .use(_ => IO.sleep(70.minutes))
      logged <- logger.logged
    yield logged

    TestControl
      .executeEmbed(program)
      .map: logged =>
        assertEquals(
          logged.count(_.isInstanceOf[StructuredTestingLogger.WARN]),
          1,
          logged.toString
        )

  test(
    "neither the secret nor the session token reaches a log line or toString"
  ):
    val program = for
      (_, _, client) <- endpoint(Set(2))
      logger = StructuredTestingLogger.impl[IO]()
      rendered <- ContainerCredentials
        .resource[IO](client, credentialsUri, logger)
        .use(credentials => IO.sleep(56.minutes) >> credentials.map(_.toString))
      logged <- logger.logged
    yield (rendered, logged.map(_.toString).mkString("\n"))

    TestControl
      .executeEmbed(program)
      .map:
        case (rendered, logs) =>
          List(rendered, logs).foreach: text =>
            assert(!text.contains("secret-example"), text)
            assert(!text.contains("token-example"), text)
end ContainerCredentialsSuite
