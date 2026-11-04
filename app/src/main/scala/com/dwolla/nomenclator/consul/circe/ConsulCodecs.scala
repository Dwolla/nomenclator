package com.dwolla.nomenclator.consul.circe

import cats.syntax.all.*
import com.dwolla.nomenclator.consul.model.*
import com.dwolla.nomenclator.registration.*
import com.dwolla.nomenclator.registration.settings.GoDuration
import io.circe.*
import io.circe.syntax.*

import scala.concurrent.duration.*

/** The JSON Consul's agent API speaks. The decoders normalize what agents send:
  * an empty `ServiceID`, a `null` `Tags`, a missing, `null`, or zero-valued
  * `Definition`, and empty top-level check fields all mean "nothing".
  */
object ConsulCodecs:

  /** The body of `PUT /v1/agent/service/register`: the same fields registrator
    * sends, each omitted when empty.
    */
  val registerBody: Encoder[Registration] = Encoder.instance: r =>
    Json
      .obj(
        "ID" -> r.id.value.asJson,
        "Name" -> r.name.value.asJson,
        "Tags" -> Option
          .when(r.tags.nonEmpty)(r.tags.map(_.value).asJson)
          .getOrElse(Json.Null),
        "Address" -> Option
          .when(r.address.value.nonEmpty)(r.address.value.asJson)
          .getOrElse(Json.Null),
        "Port" -> Option
          .when(r.port.value.value != 0)(r.port.value.value.asJson)
          .getOrElse(Json.Null),
        "Check" -> r.check.map(checkBody).getOrElse(Json.Null)
      )
      .dropNullValues

  private def checkBody(check: Check): Json = check match
    case Check.Http(url, interval, timeout) =>
      targetBody("HTTP", url, interval, timeout)
    case Check.Tcp(target, interval, timeout) =>
      targetBody("TCP", target, interval, timeout)

  private def targetBody
    (kind: String, target: String, interval: FiniteDuration, timeout: Option[FiniteDuration]): Json =
    Json
      .obj(
        kind -> target.asJson,
        "Interval" -> GoDuration.render(interval).asJson,
        "Timeout" -> timeout.map(GoDuration.render).asJson
      )
      .dropNullValues

  private val agentService: Decoder[AgentService] = Decoder.instance: c =>
    (
      c.get[String]("ID").map(ServiceId(_)),
      c.get[String]("Service").map(ServiceName(_)),
      c.getOrElse[Option[List[String]]]("Tags")(None)
        .map(_.getOrElse(Nil).map(Tag(_))),
      c.getOrElse[String]("Address")("").map(ServiceAddress(_)),
      c.getOrElse[Int]("Port")(0)
    ).mapN(AgentService.apply)

  /** `GET /v1/agent/services`: a map keyed by service ID.
    */
  val agentServices: Decoder[List[AgentService]] =
    Decoder
      .decodeMap[String, AgentService](using
        KeyDecoder.decodeKeyString,
        agentService
      )
      .map(_.values.toList)

  /** A duration as Consul spells it: a Go duration string, or integer
    * nanoseconds. Zero or empty means unset.
    */
  private val definitionDuration: Decoder[Option[String]] =
    Decoder
      .decodeOption(using
        Decoder.decodeString
          .or(Decoder.decodeLong.map(nanos => GoDuration.render(nanos.nanos)))
      )
      .map(_.filterNot(s => s.isEmpty || isZeroDuration(s)))

  private def isZeroDuration(s: String): Boolean =
    GoDuration.parse(s).exists(_ == Duration.Zero)

  private def nonEmpty(s: Option[String]): Option[String] = s.filter(_.nonEmpty)

  /** `Definition`, as agents that report one fill it in.
    */
  private val checkDefinition: Decoder[Option[CheckDefinition]] =
    Decoder.instance: c =>
      (
        c.getOrElse[Option[String]]("HTTP")(None).map(nonEmpty),
        c.getOrElse[Option[String]]("TCP")(None).map(nonEmpty),
        c.getOrElse[Option[String]]("Interval")(None)(using definitionDuration),
        c.getOrElse[Option[String]]("Timeout")(None)(using definitionDuration)
      ).mapN(CheckDefinition(None, _, _, _, _))
        .map: d =>
          Option.when(
            d.http.isDefined || d.tcp.isDefined || d.interval.isDefined
          )(d)

  /** The `Type`, `Interval`, and `Timeout` that Consul 1.21 reports beside the
    * check rather than in `Definition`, with an empty string for an unset
    * field.
    */
  private val typeAndTiming: Decoder[Option[CheckDefinition]] =
    Decoder.instance: c =>
      (
        c.getOrElse[Option[String]]("Type")(None).map(nonEmpty),
        c.getOrElse[Option[String]]("Interval")(None)(using definitionDuration),
        c.getOrElse[Option[String]]("Timeout")(None)(using definitionDuration)
      ).mapN((kind, interval, timeout) =>
        Option.when(kind.isDefined || interval.isDefined)(
          CheckDefinition(kind, None, None, interval, timeout)
        )
      )

  /** Undecodable values leave that part of the definition unreported rather
    * than failing the whole response: a foreign registration must not break a
    * pass.
    */
  private def reportedDefinition(c: HCursor): Option[CheckDefinition] =
    val fromDefinition = c
      .downField("Definition")
      .focus
      .filterNot(_.isNull)
      .flatMap(checkDefinition.decodeJson(_).toOption)
      .flatten
    val fromCheck = typeAndTiming(c).toOption.flatten
    fromDefinition
      .map(d => d.copy(kind = fromCheck.flatMap(_.kind)))
      .orElse(fromCheck)

  private val agentCheck: Decoder[AgentCheck] = Decoder.instance: c =>
    (
      c.get[String]("CheckID").map(CheckId(_)),
      c.getOrElse[Option[String]]("ServiceID")(None)
        .map(nonEmpty(_).map(ServiceId(_))),
      reportedDefinition(c).asRight[DecodingFailure]
    ).mapN(AgentCheck.apply)

  /** `GET /v1/agent/checks`: a map keyed by check ID.
    */
  val agentChecks: Decoder[List[AgentCheck]] =
    Decoder
      .decodeMap[String, AgentCheck](using
        KeyDecoder.decodeKeyString,
        agentCheck
      )
      .map(_.values.toList)

  /** `GET /v1/agent/self`: the address this agent is a member of the cluster
    * under. Unlike `Config`, which agents omit unless debugging is enabled,
    * `Member` is always present.
    */
  val memberAddress: Decoder[ServiceAddress] =
    Decoder.instance(
      _.downField("Member").get[String]("Addr").map(ServiceAddress(_))
    )
end ConsulCodecs
