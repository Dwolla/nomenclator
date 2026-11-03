package com.dwolla.nomenclator.gen

import cats.syntax.all.*
import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.registration.{Check, Registration}
import com.dwolla.nomenclator.settings.GoDuration
import org.scalacheck.{Cogen, Gen}

import scala.concurrent.duration.*

/** Desired registrations and the agent states they are planned against.
  */
object PlanGen:
  private val interval: Gen[FiniteDuration] =
    Gen.choose(1, 120).map(_.seconds)

  val check: Gen[Check] =
    for
      target <- ModelGen.identifier
      every <- interval
      limit <- Gen.option(interval)
      c <- Gen.oneOf(
        Check.Http(s"http://$target/health", every, limit),
        Check.Tcp(s"$target:80", every, limit)
      )
    yield c

  /** A registration whose ID has the host-shaped form `host:name:port`.
    */
  def registration(host: HostName): Gen[Registration] =
    for
      name <- ModelGen.serviceName
      containerName <- ModelGen.dockerName
      port <- ModelGen.hostPort
      tags <- Gen.listOfN(2, ModelGen.tag)
      address <- ModelGen.identifier
      c <- Gen.option(check)
    yield Registration(
      ServiceId(s"${host.value}:${containerName.value}:${port.value.value}"),
      name,
      tags,
      address,
      port,
      c
    )

  /** Registrations with distinct IDs, as the registrations computation yields.
    */
  def registrations(host: HostName): Gen[List[Registration]] =
    Gen
      .listOf(registration(host))
      .map(_.distinctBy(_.id.value))

  def definitionOf(check: Check): CheckDefinition =
    check match
      case Check.Http(url, every, limit) =>
        CheckDefinition(
          None,
          url.some,
          None,
          GoDuration.render(every).some,
          limit.map(GoDuration.render)
        )
      case Check.Tcp(target, every, limit) =>
        CheckDefinition(
          None,
          None,
          target.some,
          GoDuration.render(every).some,
          limit.map(GoDuration.render)
        )

  def serviceOf(r: Registration): AgentService =
    AgentService(r.id, r.name, r.tags, r.address, r.port.value.value)

  /** The check Consul assigns to a registration's check: `service:<id>`.
    */
  def registrationCheckId(id: ServiceId): String = s"service:${id.value}"

  /** The check `consul maint` adds to a service.
    */
  def maintenanceCheckFor(id: ServiceId): AgentCheck =
    AgentCheck(s"_service_maintenance:${id.value}", id.some, None)

  def agentCheckFor(id: ServiceId, c: Check): AgentCheck =
    AgentCheck(registrationCheckId(id), id.some, definitionOf(c).some)

  private def reportedCheck(id: ServiceId, expected: Check): Gen[AgentCheck] =
    Gen
      .oneOf(
        definitionOf(expected).some,
        definitionOf(expected).copy(interval = "999h".some).some,
        None
      )
      .map(AgentCheck(registrationCheckId(id), id.some, _))

  /** The registration check, as the agent might hold it, and sometimes a
    * maintenance check or a check of some other name for the same service.
    */
  private def checksFor(id: ServiceId, expected: Option[Check]): Gen[List[AgentCheck]] =
    for
      own <- Gen.frequency(
        4 -> Gen.const(expected.map(agentCheckFor(id, _))),
        2 -> Gen.const(None),
        2 -> expected.fold(Gen.const(none[AgentCheck]))(
          reportedCheck(id, _).map(_.some)
        ),
        1 -> Gen.const(AgentCheck(registrationCheckId(id), id.some, None).some)
      )
      others <- Gen.frequency(
        4 -> Gen.const(Nil),
        2 -> Gen.const(List(maintenanceCheckFor(id))),
        1 -> ModelGen.identifier.map(n =>
          List(
            AgentCheck(
              n,
              id.some,
              definitionOf(Check.Tcp("x:1", 1.second, None)).some
            )
          )
        )
      )
    yield own.toList ++ others

  private def drifted(r: Registration): Gen[AgentService] =
    val s = serviceOf(r)
    Gen.oneOf(
      Gen.const(s),
      Gen.const(s),
      Gen.const(s),
      Gen.const(s.copy(tags = Tag("drift") :: s.tags)),
      Gen.const(s.copy(tags = s.tags.reverse :+ Tag("x"))),
      Gen.const(s.copy(port = s.port + 1)),
      Gen.const(s.copy(address = s.address + "x")),
      Gen.const(s.copy(name = ServiceName(s.name.value + "x")))
    )

  private def hostShapedId(host: HostName): Gen[ServiceId] =
    for
      name <- ModelGen.dockerName
      port <- Gen.choose(1, 65535)
      suffix <- Gen.oneOf("", ":udp")
    yield ServiceId(s"${host.value}:${name.value}:$port$suffix")

  private def foreignId(host: HostName): Gen[ServiceId] =
    Gen
      .oneOf(
        ModelGen.identifier.map(i => s"other-$i:svc:80"),
        ModelGen.identifier,
        ModelGen.identifier.map(i => s"$i:svc:80:tcp"),
        ModelGen.identifier.map(i => s"${host.value}:$i")
      )
      .map(ServiceId(_))
      .suchThat(id => !ServiceIds.owns(host)(id))

  private type Entry = (AgentService, List[AgentCheck])

  private def extra(id: ServiceId): Gen[Entry] =
    for
      name <- ModelGen.serviceName
      tags <- Gen.listOf(ModelGen.tag)
      port <- Gen.choose(1, 65535)
      expected <- Gen.option(check)
      checks <- checksFor(id, expected)
    yield (AgentService(id, name, tags, "10.0.0.9", port), checks)

  /** An agent state around the desired registrations: some in sync, some
    * drifted or absent, plus owned leftovers, services that aren't ours, and
    * checks attached to nothing.
    */
  def agentState(host: HostName, desired: List[Registration]): Gen[AgentState] =
    for
      kept <- Gen.sequence[List[Option[Entry]], Option[Entry]](desired.map: r =>
        Gen.frequency(
          1 -> Gen.const(None),
          5 -> drifted(r).flatMap(s => checksFor(r.id, r.check).map(cs => (s, cs).some))
        ))
      leftovers <- Gen.listOf(hostShapedId(host).flatMap(extra))
      foreign <- Gen.listOf(foreignId(host).flatMap(extra))
      orphans <- Gen.listOf(
        ModelGen.identifier.map(i => AgentCheck(i, None, None))
      )
    yield
      val all = kept.flatten ++ leftovers ++ foreign
      AgentState(
        all.map(_._1).distinctBy(_.id.value),
        all.flatMap(_._2) ++ orphans
      )

  /** Which registrations the simulated agent reports a check definition for.
    */
  val reportsDefinition: Gen[ServiceId => Boolean] =
    Gen.function1[ServiceId, Boolean](Gen.oneOf(true, false))(using
      Cogen[String].contramap[ServiceId](_.value)
    )
end PlanGen
