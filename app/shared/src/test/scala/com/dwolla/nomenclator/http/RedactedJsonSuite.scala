package com.dwolla.nomenclator.http

import cats.effect.*
import io.circe.Decoder
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.headers.`Content-Type`

class RedactedJsonSuite extends CatsEffectSuite:
  private val secret = "s3cret-value-from-the-body"

  private final case class Port(number: Int)
  private given Decoder[Port] =
    Decoder.instance(_.get[Int]("Port").map(Port(_)))

  private def decoded(body: String): IO[Either[Throwable, Port]] =
    Response[IO]()
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))
      .as[Port](using summon, RedactedJson.decoder[IO, Port])
      .attempt

  /** Every message in the failure's cause chain.
    */
  private def messages(e: Throwable): List[String] =
    List
      .unfold(Option(e))(
        _.map(t => (String.valueOf(t.getMessage), Option(t.getCause)))
      )

  private def assertBodyAbsent(result: Either[Throwable, Port]): List[String] =
    result match
      case Left(e) =>
        val all = messages(e)
        assert(!all.exists(_.contains(secret)), all)
        all
      case Right(port) => fail(s"expected a failure, decoded $port")

  test("a body that decodes is returned"):
    decoded("""{"Port":8500}""").map(assertEquals(_, Right(Port(8500))))

  test("malformed JSON fails without echoing the body"):
    decoded(s"""{"Port": $secret""").map: result =>
      assertBodyAbsent(result)
      assert(result.left.exists(_.isInstanceOf[MalformedMessageBodyFailure]))

  test("a wrongly typed value fails naming the field, not the value"):
    decoded(s"""{"Port":"$secret"}""").map: result =>
      val all = assertBodyAbsent(result)
      assert(all.exists(_.contains("Port")), all)
      assert(result.left.exists(_.isInstanceOf[InvalidMessageBodyFailure]))

  test("a missing field fails naming the field"):
    decoded(s"""{"Other":"$secret"}""").map: result =>
      val all = assertBodyAbsent(result)
      assert(all.exists(_.contains("Port")), all)
end RedactedJsonSuite
