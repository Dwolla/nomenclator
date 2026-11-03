package com.dwolla.nomenclator.http

import cats.Functor
import cats.effect.Concurrent
import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure}
import org.http4s.*
import org.http4s.circe.*

/** Decodes JSON response bodies so that a failure never carries any part of the
  * body, in its message or in its cause, where logs and spans would record it.
  * A failure says where decoding stopped and why, in terms of the JSON's shape:
  * the path, a missing field, or the type expected.
  */
object RedactedJson:
  private val redacted = "<redacted>"

  def decoder[F[_]: Concurrent, A: Decoder]: EntityDecoder[F, A] =
    withoutBody(jsonOfSensitive[F, A](_ => redacted))

  private def withoutBody[F[_]: Functor, A](decoder: EntityDecoder[F, A]): EntityDecoder[F, A] =
    decoder.transform(_.leftMap:
      // The parser's own message quotes the input around the error.
      case MalformedMessageBodyFailure(details, _) =>
        MalformedMessageBodyFailure(details)
      case InvalidMessageBodyFailure(_, cause) =>
        InvalidMessageBodyFailure(
          s"Could not decode JSON: ${cause.fold(redacted)(describe)}"
        )
      case other => other)

  private def describe(cause: Throwable): String = cause match
    case failure: DecodingFailure   => describe(failure)
    case DecodingFailures(failures) => describe(failures.head)
    case _                          => redacted

  /** A custom reason is kept: Nomenclator's decoders write those, and never
    * quote the value they reject.
    */
  private def describe(failure: DecodingFailure): String =
    val reason = failure.reason match
      case DecodingFailure.Reason.MissingField                      => "missing required field"
      case DecodingFailure.Reason.WrongTypeExpectation(expected, _) =>
        s"expected $expected"
      case DecodingFailure.Reason.CustomReason(message) => message
    s"$reason at ${failure.pathToRootString.getOrElse("the top level")}"
end RedactedJson
