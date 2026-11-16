package com.dwolla.nomenclator.ecs.http4s

import cats.syntax.all.*
import com.dwolla.nomenclator.ecs.model.*
import io.circe.*

/** The JSON the ECS agent's introspection API speaks. Anything wrong with a
  * task fails the whole decode: a partial task list must never look like a
  * complete one, because the reconciler deregisters whatever it doesn't see.
  * Port bindings are the exception, since the agent reports bindings that can't
  * be registered (see [[portBindings]] and [[isAwsvpc]]).
  */
object IntrospectionCodecs:

  /** `GET /v1/metadata`. The agent leaves `ContainerInstanceArn` null or empty
    * until it has registered, which is not an instance Nomenclator can serve.
    */
  val metadata: Decoder[InstanceMetadata] = Decoder.instance: c =>
    (
      c.get[String]("Cluster"),
      c.getOrElse[Option[String]]("ContainerInstanceArn")(None)
        .flatMap(
          _.filter(_.nonEmpty).toRight(
            DecodingFailure(
              "ContainerInstanceArn is empty: the agent has not registered yet",
              c.history
            )
          )
        )
    ).mapN(InstanceMetadata.apply)

  private val status: Decoder[TaskStatus] =
    Decoder.decodeString.map(TaskStatus.fromString)

  // `DesiredStatus` is omitted by the agent in some states.
  private def desiredStatus(c: HCursor): Decoder.Result[TaskStatus] =
    c.getOrElse[Option[String]]("DesiredStatus")(None)
      .map(_.fold(TaskStatus.Other(""))(TaskStatus.fromString))

  private val revision: Decoder[Int] =
    Decoder.decodeString.emap(s => s.toIntOption.toRight("Version must be an integer"))

  /** Docker names containers with a leading `/`; registrator used the name
    * without it.
    */
  private def dockerName(raw: String): DockerName =
    DockerName(raw.stripPrefix("/"))

  /** Keeps only what can be registered: entries without a host port (or with
    * port 0, a bridge container that has not started), with ports outside
    * 0..65535, or with a protocol other than tcp/udp are dropped. A missing
    * protocol means tcp. The agent reports each Docker binding separately, so
    * the IPv4 and IPv6 bindings of one port are duplicates; the first is kept.
    * An entry that is malformed, rather than merely unregisterable (not an
    * object, no container port, a field of the wrong type), fails the decode.
    */
  private def portBindings(c: HCursor): Decoder.Result[List[PortBinding]] =
    c.getOrElse[Option[List[Json]]]("Ports")(None)
      .flatMap(_.getOrElse(Nil).traverse(portBinding))
      .map(_.flatten.distinct)

  // circe's own Long decoder also accepts the string "32768".
  private val jsonInteger: Decoder[Long] =
    Decoder.decodeJsonNumber.emap(
      _.toLong.toRight("expected an integer JSON number")
    )

  private def portBinding(entry: Json): Decoder.Result[Option[PortBinding]] =
    val c = entry.hcursor
    (
      c.get[Long]("ContainerPort")(using jsonInteger),
      c.get[Option[Long]]("HostPort")(using
        Decoder.decodeOption(using jsonInteger)
      ),
      c.get[Option[String]]("Protocol")
    ).mapN: (containerPort, hostPort, protocol) =>
      for
        hostPort <- hostPort.filter(_ != 0L)
        protocol <- protocol
          .filter(_.nonEmpty)
          .fold(Protocol.Tcp.some)(Protocol.fromString(_).toOption)
        cp <- port(containerPort)(ContainerPort.from)
        hp <- port(hostPort)(HostPort.from)
      yield PortBinding(cp, hp, protocol)
  end portBinding

  private def port[A](value: Long)(from: Int => Either[String, A]): Option[A] =
    Option.when(value.isValidInt)(value.toInt).flatMap(from(_).toOption)

  private val networkMode: Decoder[String] =
    Decoder.instance(_.get[String]("NetworkMode"))

  /** The agent reports `Networks` only for awsvpc containers, which listen on
    * their own network interface. It still reports their ports, with the host
    * port equal to the container port, but nothing listens on the host there,
    * and registrator, seeing no published ports, registered nothing. A
    * `Networks` that is present but malformed fails the decode, so an awsvpc
    * container can't be mistaken for one that publishes its ports.
    */
  private def isAwsvpc(c: HCursor): Decoder.Result[Boolean] =
    c.getOrElse[Option[List[String]]]("Networks")(None)(using
      Decoder.decodeOption(using Decoder.decodeList(using networkMode))
    ).map(_.getOrElse(Nil).exists(_.equalsIgnoreCase("awsvpc")))

  private val container: Decoder[EcsContainer] = Decoder.instance: c =>
    (
      c.get[String]("Name").map(ContainerName(_)),
      c.get[String]("DockerName").map(dockerName),
      c.get[String]("Image").map(ImageName(_)),
      (portBindings(c), isAwsvpc(c)).mapN((ports, awsvpc) => if (awsvpc) Nil else ports)
    ).mapN(EcsContainer.apply)

  private val task: Decoder[EcsTask] = Decoder.instance: c =>
    (
      c.get[String]("Arn").map(TaskArn(_)),
      (
        c.get[String]("Family").map(TaskFamily(_)),
        c.get[Int]("Version")(using revision)
      ).mapN(TaskDefinitionRef.apply),
      c.get[TaskStatus]("KnownStatus")(using status),
      desiredStatus(c),
      requiredList("Containers")(c)(using container)
    ).mapN(EcsTask.apply)

  /** A list the agent always sends. Go encodes an empty slice as `null`, so
    * `null` means empty, but an absent key means the response isn't what we
    * think it is, and decoding it as empty would look like "nothing is
    * running".
    */
  private def requiredList[A: Decoder](key: String)(c: HCursor): Decoder.Result[List[A]] =
    c.downField(key).focus match
      case None =>
        DecodingFailure(s"Missing required field $key", c.history).asLeft
      case Some(j) if j.isNull => Nil.asRight
      case Some(_)             => c.get[List[A]](key)

  /** `GET /v1/tasks`: `{"Tasks": [...]}`.
    */
  val tasks: Decoder[List[EcsTask]] =
    Decoder.instance(requiredList("Tasks")(_)(using task))
end IntrospectionCodecs
