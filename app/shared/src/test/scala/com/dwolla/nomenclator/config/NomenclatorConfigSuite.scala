package com.dwolla.nomenclator.config

import cats.effect.*
import cats.syntax.all.*
import ciris.*
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.reconcile.Mode
import munit.CatsEffectSuite
import org.http4s.syntax.all.*
import smithy4s.aws.AwsRegion

import scala.concurrent.duration.*

class NomenclatorConfigSuite extends CatsEffectSuite:
  private val token = "s3cret-token-value"

  /** Loads the configuration from exactly these environment variables.
    */
  private def load(variables: (String, String)*): IO[Either[ConfigError, NomenclatorConfig]] =
    NomenclatorConfig.fromEnvironment(TestEnvironment(variables*)).attempt[IO]

  private def loaded(variables: (String, String)*): IO[NomenclatorConfig] =
    load(variables*).map(
      _.fold(e => fail(e.messages.mkString_("; ")), identity)
    )

  /** The error messages, which must name every variable in `names`.
    */
  private def assertFailsNaming(variables: (String, String)*)(names: String*): IO[Unit] =
    load(variables*).map:
      case Left(error) =>
        val messages = error.messages.mkString_("; ")
        names.foreach(name => assert(messages.contains(name), messages))
      case Right(config) => fail(s"expected a failure, loaded $config")

  test("an empty environment gives the defaults"):
    loaded().map: config =>
      assertEquals(config.mode, Mode.Shadow)
      assertEquals(config.consulAddress, uri"http://127.0.0.1:8500")
      assertEquals(config.consulToken, None)
      assertEquals(config.interval, 5.seconds)
      assertEquals(config.passTimeout, 30.seconds)
      assertEquals(config.lookupTimeout, 5.seconds)
      assertEquals(config.failureLimit, 2.minutes)
      assertEquals(config.globalTags, Nil)
      assertEquals(config.hostName, None)
      assertEquals(config.address, None)
      assertEquals(config.ecsAgent, uri"http://localhost:51678")
      assertEquals(config.credentialsRelativeUri, None)
      assertEquals(config.credentialsFullUri, None)
      assertEquals(config.awsRegion, None)
      assertEquals(config.ecsEndpoint, None)

  test("NOMENCLATOR_MODE=apply writes the plan"):
    loaded("NOMENCLATOR_MODE" -> "apply").map(c => assertEquals(c.mode, Mode.Apply))

  test("NOMENCLATOR_MODE=bogus fails with a message naming the variable"):
    assertFailsNaming("NOMENCLATOR_MODE" -> "bogus")("NOMENCLATOR_MODE")

  test("every invalid variable is reported at once"):
    assertFailsNaming(
      "NOMENCLATOR_MODE" -> "bogus",
      "NOMENCLATOR_INTERVAL" -> "often"
    )("NOMENCLATOR_MODE", "NOMENCLATOR_INTERVAL")

  test("durations use Go's syntax"):
    loaded(
      "NOMENCLATOR_INTERVAL" -> "2.5s",
      "NOMENCLATOR_PASS_TIMEOUT" -> "45s",
      "NOMENCLATOR_LOOKUP_TIMEOUT" -> "1500ms",
      "NOMENCLATOR_FAILURE_LIMIT" -> "1m30s"
    ).map: config =>
      assertEquals(config.interval, 2500.millis)
      assertEquals(config.passTimeout, 45.seconds)
      assertEquals(config.lookupTimeout, 1500.millis)
      assertEquals(config.failureLimit, 90.seconds)

  test("an unparseable duration fails naming its variable"):
    assertFailsNaming("NOMENCLATOR_FAILURE_LIMIT" -> "2 minutes")(
      "NOMENCLATOR_FAILURE_LIMIT"
    )

  List(
    "NOMENCLATOR_INTERVAL",
    "NOMENCLATOR_PASS_TIMEOUT",
    "NOMENCLATOR_LOOKUP_TIMEOUT",
    "NOMENCLATOR_FAILURE_LIMIT"
  ).foreach: name =>
    test(s"$name must be positive"):
      assertFailsNaming(name -> "0s")(name) >>
        assertFailsNaming(name -> "-5s")(name)

  test(
    "the pass timeout must leave one lookup timeout after the lookup phase's deadline"
  ):
    assertFailsNaming(
      "NOMENCLATOR_PASS_TIMEOUT" -> "17499ms",
      "NOMENCLATOR_LOOKUP_TIMEOUT" -> "5s"
    )("NOMENCLATOR_PASS_TIMEOUT", "NOMENCLATOR_LOOKUP_TIMEOUT") >>
      loaded(
        "NOMENCLATOR_PASS_TIMEOUT" -> "17500ms",
        "NOMENCLATOR_LOOKUP_TIMEOUT" -> "5s"
      ).void

  test("the pass timeout must be shorter than the failure limit"):
    assertFailsNaming(
      "NOMENCLATOR_PASS_TIMEOUT" -> "2m",
      "NOMENCLATOR_FAILURE_LIMIT" -> "2m"
    )("NOMENCLATOR_PASS_TIMEOUT", "NOMENCLATOR_FAILURE_LIMIT")

  test("the interval must not exceed the pass timeout"):
    assertFailsNaming(
      "NOMENCLATOR_INTERVAL" -> "31s"
    )("NOMENCLATOR_INTERVAL", "NOMENCLATOR_PASS_TIMEOUT") >>
      loaded("NOMENCLATOR_INTERVAL" -> "30s").void

  test("CONSUL_HTTP_ADDR without a scheme means plain HTTP"):
    loaded("CONSUL_HTTP_ADDR" -> "consul.example:8500").map(c =>
      assertEquals(c.consulAddress, uri"http://consul.example:8500")
    )

  test("CONSUL_HTTP_ADDR keeps an explicit scheme"):
    loaded("CONSUL_HTTP_ADDR" -> "https://consul.example:8501").map(c =>
      assertEquals(c.consulAddress, uri"https://consul.example:8501")
    )

  test("an unusable CONSUL_HTTP_ADDR fails naming the variable"):
    assertFailsNaming("CONSUL_HTTP_ADDR" -> "http://")("CONSUL_HTTP_ADDR")

  test("CONSUL_HTTP_TOKEN is kept as a secret"):
    loaded("CONSUL_HTTP_TOKEN" -> token).map: config =>
      assertEquals(config.consulToken.map(_.value), Some(token))
      assert(!config.toString.contains(token), config.toString)

  test("an empty CONSUL_HTTP_TOKEN means no token"):
    loaded("CONSUL_HTTP_TOKEN" -> "").map(c => assertEquals(c.consulToken, None))

  test("the log context never contains the token"):
    loaded("CONSUL_HTTP_TOKEN" -> token).map: config =>
      val context = config.logContext
      assert(!context.mkString.contains(token), context.toString)
      assertEquals(context.get("consul_token"), Some("<redacted>"))

  test("NOMENCLATOR_TAGS splits on commas and keeps each part as it is"):
    loaded("NOMENCLATOR_TAGS" -> "a,,b, c").map(c => assertEquals(c.globalTags, List("a", "", "b", " c").map(Tag(_))))

  test("an empty NOMENCLATOR_TAGS means no tags"):
    loaded("NOMENCLATOR_TAGS" -> "").map(c => assertEquals(c.globalTags, Nil))

  test("NOMENCLATOR_HOSTNAME overrides the host's name"):
    loaded("NOMENCLATOR_HOSTNAME" -> "ip-10-0-0-1").map(c => assertEquals(c.hostName.map(_.value), Some("ip-10-0-0-1")))

  test("an invalid NOMENCLATOR_HOSTNAME fails naming the variable"):
    assertFailsNaming("NOMENCLATOR_HOSTNAME" -> "a:b")("NOMENCLATOR_HOSTNAME")

  test("NOMENCLATOR_ADDRESS overrides the advertised address"):
    loaded("NOMENCLATOR_ADDRESS" -> "10.0.0.9").map(c => assertEquals(c.address, Some(ServiceAddress("10.0.0.9"))))

  test("an empty NOMENCLATOR_ADDRESS fails naming the variable"):
    assertFailsNaming("NOMENCLATOR_ADDRESS" -> "")("NOMENCLATOR_ADDRESS")

  test("the AWS variables are read as they are"):
    loaded(
      "AWS_REGION" -> "us-west-2",
      "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI" -> "/v2/credentials/abc",
      "AWS_CONTAINER_CREDENTIALS_FULL_URI" -> "http://127.0.0.1:9911/creds",
      "AWS_ENDPOINT_URL_ECS" -> "http://127.0.0.1:9912",
      "NOMENCLATOR_ECS_AGENT_URL" -> "http://127.0.0.1:9913"
    ).map: config =>
      assertEquals(config.awsRegion, Some(AwsRegion("us-west-2")))
      assertEquals(
        config.credentialsRelativeUri,
        Some(path"/v2/credentials/abc")
      )
      assertEquals(
        config.credentialsFullUri,
        Some(uri"http://127.0.0.1:9911/creds")
      )
      assertEquals(config.ecsEndpoint, Some(uri"http://127.0.0.1:9912"))
      assertEquals(config.ecsAgent, uri"http://127.0.0.1:9913")

  // Each would move the request off ECS's credentials host if it were
  // appended to it as text.
  List(
    ".evil.com/x",
    "@evil.com/x",
    "//evil.com/x",
    ":8080/x",
    "v2/credentials/abc",
    "/v2/credentials/abc?x=1",
    "/v2/credentials/abc#x",
    ""
  ).foreach: refused =>
    test(s"AWS_CONTAINER_CREDENTIALS_RELATIVE_URI may not be '$refused'"):
      assertFailsNaming("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI" -> refused)(
        "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"
      )

  List(
    "http://127.0.0.1:9911/creds",
    "http://127.1.2.3/creds",
    "http://localhost:9911/creds",
    "http://[::1]:9911/creds",
    "http://169.254.170.2/v2/credentials",
    "http://169.254.170.23/v1/credentials",
    "http://[fd00:ec2::23]/v1/credentials",
    "https://credentials.example.com/creds"
  ).foreach: allowed =>
    test(s"AWS_CONTAINER_CREDENTIALS_FULL_URI may be $allowed"):
      loaded("AWS_CONTAINER_CREDENTIALS_FULL_URI" -> allowed).map(c =>
        assertEquals(c.credentialsFullUri.map(_.renderString), Some(allowed))
      )

  List(
    "http://10.0.0.5/creds",
    "http://credentials.example.com/creds",
    "http://169.254.169.254/latest/meta-data"
  ).foreach: refused =>
    test(s"AWS_CONTAINER_CREDENTIALS_FULL_URI may not be plain-HTTP $refused"):
      assertFailsNaming("AWS_CONTAINER_CREDENTIALS_FULL_URI" -> refused)(
        "AWS_CONTAINER_CREDENTIALS_FULL_URI"
      )

  test("AWS_ENDPOINT_URL_ECS may end in a slash"):
    loaded("AWS_ENDPOINT_URL_ECS" -> "http://127.0.0.1:9912/").map(c =>
      assertEquals(c.ecsEndpoint, Some(uri"http://127.0.0.1:9912/"))
    )

  test("AWS_ENDPOINT_URL_ECS with a path fails naming the variable"):
    assertFailsNaming("AWS_ENDPOINT_URL_ECS" -> "http://127.0.0.1:9912/ecs")(
      "AWS_ENDPOINT_URL_ECS"
    )

  test("an unusable AWS_ENDPOINT_URL_ECS fails naming the variable"):
    assertFailsNaming("AWS_ENDPOINT_URL_ECS" -> "not a url")(
      "AWS_ENDPOINT_URL_ECS"
    )
end NomenclatorConfigSuite
