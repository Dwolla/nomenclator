package com.dwolla.nomenclator.plan

import cats.data.NonEmptyList
import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.gen.PlanGen
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.registration.{Check, Registration}
import munit.FunSuite

import scala.concurrent.duration.*

class ShadowReportSuite extends FunSuite:
  private val tcp = Check.Tcp("10.0.0.1:8080", 10.seconds, None)
  private val web = Registration(
    ServiceId("ip-10-0-0-1:web:8080"),
    ServiceName("web"),
    List(Tag("a")),
    "10.0.0.1",
    HostPort.from(8080).fold(fail(_), identity),
    Some(tcp)
  )

  /** What registrator would have registered: another ID shape, extra tags.
    */
  private val registrators = AgentService(
    ServiceId("abc123:web:8080"),
    ServiceName("web"),
    List(Tag("a"), Tag("Version-x")),
    "10.0.0.1",
    8080
  )

  private def withCheck(s: AgentService, c: Option[Check] = Some(tcp)) =
    AgentState(
      List(s),
      c.toList.map(PlanGen.agentCheckFor(s.id, _))
    )

  test("a matching service with identical tags and check has no difference"):
    assertEquals(
      ShadowReport.compare(
        List(web),
        withCheck(registrators.copy(tags = web.tags))
      ),
      Nil
    )

  test("a registrator-shaped entry with extra tags differs only in tags"):
    assertEquals(
      ShadowReport.compare(List(web), withCheck(registrators)),
      List(
        Difference.Different(web, registrators, NonEmptyList.one("tags"))
      )
    )

  test("a check differing from the reported definition differs in check"):
    val s = registrators.copy(tags = web.tags)
    val other = Check.Tcp("10.0.0.1:8080", 99.seconds, None)
    assertEquals(
      ShadowReport.compare(List(web), withCheck(s, Some(other))),
      List(Difference.Different(web, s, NonEmptyList.one("check")))
    )

  test("tags and check are named in a fixed order"):
    val other = Check.Tcp("10.0.0.1:8080", 99.seconds, None)
    assertEquals(
      ShadowReport.compare(List(web), withCheck(registrators, Some(other))),
      List(
        Difference.Different(
          web,
          registrators,
          NonEmptyList.of("tags", "check")
        )
      )
    )

  test("a missing entry is Missing"):
    assertEquals(
      ShadowReport.compare(List(web), AgentState(Nil, Nil)),
      List(Difference.Missing(web))
    )

  test("a different port, address, or name does not match"):
    val s = registrators.copy(tags = web.tags)
    List(
      s.copy(port = 1),
      s.copy(address = "x"),
      s.copy(name = ServiceName("y"))
    )
      .foreach: other =>
        assertEquals(
          ShadowReport.compare(List(web), withCheck(other)),
          List(Difference.Missing(web), Difference.Extra(other))
        )

  test(
    "an unmatched agent service is Extra only if registrator could have registered it"
  ):
    val owned = AgentService(
      ServiceId("ip-10-0-0-1:old:1"),
      ServiceName("old"),
      Nil,
      "10.0.0.1",
      1
    )
    val consul =
      AgentService(ServiceId("consul"), ServiceName("consul"), Nil, "", 8300)
    val handWritten =
      AgentService(ServiceId("foo-service"), ServiceName("foo"), Nil, "", 80)
    assertEquals(
      ShadowReport.compare(
        Nil,
        AgentState(List(owned, consul, registrators, handWritten), Nil)
      ),
      List(Difference.Extra(owned), Difference.Extra(registrators))
    )

  test("an agent that reports no definition judges the check by its presence"):
    val s = registrators.copy(tags = web.tags)
    val unreported = AgentState(
      List(s),
      List(AgentCheck(PlanGen.registrationCheckId(s.id), Some(s.id), None))
    )
    assertEquals(ShadowReport.compare(List(web), unreported), Nil)
    assertEquals(
      ShadowReport.compare(List(web), AgentState(List(s), Nil)),
      List(Difference.Different(web, s, NonEmptyList.one("check")))
    )

  test("a service in maintenance mode shows no check difference"):
    val s = registrators.copy(tags = web.tags)
    val state = withCheck(s)
    assertEquals(
      ShadowReport.compare(
        List(web),
        state.copy(checks = PlanGen.maintenanceCheckFor(s.id) :: state.checks)
      ),
      Nil
    )

  test("tcp and udp on one port each match their own entry"):
    val udp = web.copy(
      id = ServiceId("ip-10-0-0-1:web:8080:udp"),
      tags = List(Tag("a"), Tag("udp"))
    )
    val tcpEntry = PlanGen.serviceOf(web).copy(id = ServiceId("a:web:8080"))
    val udpEntry = PlanGen.serviceOf(udp).copy(id = ServiceId("a:web:8080:udp"))
    val state = AgentState(
      List(udpEntry, tcpEntry),
      List(
        PlanGen.agentCheckFor(tcpEntry.id, tcp),
        PlanGen.agentCheckFor(udpEntry.id, tcp)
      )
    )
    assertEquals(ShadowReport.compare(List(web, udp), state), Nil)

  test("one agent service answers only one desired registration"):
    val twin = web.copy(id = ServiceId("ip-10-0-0-1:web:8080:udp"))
    val entry = PlanGen.serviceOf(web)
    assertEquals(
      ShadowReport.compare(List(web, twin), withCheck(entry)),
      List(Difference.Missing(twin))
    )
end ShadowReportSuite
