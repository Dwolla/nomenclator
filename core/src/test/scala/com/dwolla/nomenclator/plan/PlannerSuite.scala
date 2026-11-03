package com.dwolla.nomenclator.plan

import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.gen.{ModelGen, PlanGen}
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.registration.{Check, Registration}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

import scala.concurrent.duration.*

class PlannerSuite extends ScalaCheckSuite:
  private val host = HostName.from("ip-10-0-0-1").fold(fail(_), identity)
  private val tcp = Check.Tcp("10.0.0.1:8080", 10.seconds, None)
  private val web = Registration(
    ServiceId("ip-10-0-0-1:web:8080"),
    ServiceName("web"),
    List(Tag("a"), Tag("b")),
    ServiceAddress("10.0.0.1"),
    HostPort.from(8080).fold(fail(_), identity),
    Some(tcp)
  )
  private val api = web.copy(
    id = ServiceId("ip-10-0-0-1:api:9090"),
    name = ServiceName("api"),
    port = HostPort.from(9090).fold(fail(_), identity),
    check = None
  )

  private def stateOf(rs: Registration*) = AgentState(
    rs.toList.map(PlanGen.serviceOf),
    rs.toList.flatMap(r => r.check.map(PlanGen.agentCheckFor(r.id, _)))
  )

  private def plan(desired: List[Registration], actual: AgentState) =
    Planner.plan(host, desired, actual)

  test("an identical desired and actual service gives an empty plan"):
    assertEquals(plan(List(web, api), stateOf(web, api)), Plan(Nil, Nil))

  test("a missing service is registered"):
    assertEquals(plan(List(web), stateOf()), Plan(List(web), Nil))

  test("a tag difference re-registers"):
    val drifted = web.copy(tags = List(Tag("a")))
    assertEquals(plan(List(web), stateOf(drifted)), Plan(List(web), Nil))

  test("a tag reordering re-registers"):
    val drifted = web.copy(tags = web.tags.reverse)
    assertEquals(plan(List(web), stateOf(drifted)), Plan(List(web), Nil))

  test("name, address, and port differences re-register"):
    val s = stateOf(web)
    List(
      s.services.map(_.copy(name = ServiceName("other"))),
      s.services.map(_.copy(address = ServiceAddress("10.9.9.9"))),
      s.services.map(_.copy(port = 1))
    ).foreach: services =>
      assertEquals(
        plan(List(web), s.copy(services = services)),
        Plan(List(web), Nil)
      )

  test("an owned service that is no longer desired is deregistered"):
    assertEquals(
      plan(List(web), stateOf(web, api)),
      Plan(Nil, List(api.id))
    )

  test("a service that isn't owned is never touched"):
    val registrator = AgentService(
      ServiceId("abc123:web:8080"),
      ServiceName("web"),
      Nil,
      ServiceAddress("10.0.0.1"),
      8080
    )
    val consulItself = registrator.copy(id = ServiceId("consul"))
    val state = AgentState(List(registrator, consulItself), Nil)
    assertEquals(plan(Nil, state), Plan(Nil, Nil))
    assertEquals(plan(List(web), state), Plan(List(web), Nil))

  test("an agent-reported check with a different interval re-registers"):
    val state = stateOf(web)
    val drifted =
      state.copy(checks = state.checks.map(c => c.copy(definition = c.definition.map(_.copy(interval = Some("99s"))))))
    assertEquals(plan(List(web), drifted), Plan(List(web), Nil))

  test("an agent that reports no definition but has one check is unchanged"):
    val state = stateOf(web)
    val unreported =
      state.copy(checks = state.checks.map(_.copy(definition = None)))
    assertEquals(plan(List(web), unreported), Plan(Nil, Nil))

  test("a service in maintenance mode is not re-registered"):
    val state = stateOf(web, api)
    val inMaintenance = state.copy(checks = state.checks ++ List(web, api).map(r => PlanGen.maintenanceCheckFor(r.id)))
    assertEquals(plan(List(web, api), inMaintenance), Plan(Nil, Nil))

  test("a desired registration that isn't host-shaped is dropped"):
    val stray = web.copy(id = ServiceId("someone-else:web:8080"))
    assertEquals(plan(List(stray, api), stateOf()), Plan(List(api), Nil))

  test("an agent that has no check for a desired check re-registers"):
    assertEquals(
      plan(List(web), stateOf(web).copy(checks = Nil)),
      Plan(List(web), Nil)
    )

  test("an agent that has a check the desired service lacks re-registers"):
    val withCheck = api.copy(check = Some(tcp))
    assertEquals(
      plan(List(api), stateOf(withCheck)),
      Plan(List(api), List(api.id))
    )

  test(
    "a service whose check was removed is replaced: deregistered and registered"
  ):
    val result = plan(List(api, web), stateOf(api.copy(check = Some(tcp)), web))
    assertEquals(result, Plan(List(api), List(api.id)))

  test(
    "a changed check, or a changed field with a check, is a plain register"
  ):
    val changedCheck =
      web.copy(check = Some(Check.Tcp("10.0.0.1:8080", 99.seconds, None)))
    assertEquals(plan(List(web), stateOf(changedCheck)), Plan(List(web), Nil))
    val changedTags = web.copy(tags = Nil)
    assertEquals(plan(List(web), stateOf(changedTags)), Plan(List(web), Nil))

  test("registrations keep desired order and deregistrations are sorted"):
    val zed = api.copy(id = ServiceId("ip-10-0-0-1:zed:1"))
    val alpha = api.copy(id = ServiceId("ip-10-0-0-1:alpha:1"))
    val result = plan(List(web, api), stateOf(zed, alpha))
    assertEquals(result.register, List(web, api))
    assertEquals(result.deregister, List(alpha.id, zed.id))

  private val scenario = for
    h <- ModelGen.hostName
    desired <- PlanGen.registrations(h)
    state <- PlanGen.agentState(h, desired)
    reports <- PlanGen.reportsDefinition
  yield (h, desired, state, reports)

  property("applying the plan converges: planning again does nothing"):
    forAll(scenario):
      case (h, desired, state, reports) =>
        val applied =
          AgentSimulation(Planner.plan(h, desired, state), state, reports)
        assertEquals(Planner.plan(h, desired, applied), Plan(Nil, Nil))

  property("applying the plan leaves exactly the desired services of ours"):
    forAll(scenario):
      case (h, desired, state, reports) =>
        val applied =
          AgentSimulation(Planner.plan(h, desired, state), state, reports)
        val ours = applied.services.filter(s => ServiceIds.owns(h)(s.id))
        assertEquals(
          ours.map(_.id.value).sorted,
          desired.map(_.id.value).sorted
        )

  property("the plan never touches a service that isn't owned"):
    forAll(scenario):
      case (h, desired, state, reports) =>
        val result = Planner.plan(h, desired, state)
        val applied = AgentSimulation(result, state, reports)
        val foreign = state.services.filterNot(s => ServiceIds.owns(h)(s.id))
        assert(result.deregister.forall(ServiceIds.owns(h)(_)))
        assertEquals(
          applied.services.filterNot(s => ServiceIds.owns(h)(s.id)),
          foreign
        )
        assertEquals(
          applied.checks.filter(c => c.serviceId.exists(id => foreign.exists(_.id == id))),
          state.checks.filter(c => c.serviceId.exists(id => foreign.exists(_.id == id)))
        )

  property("an in-sync agent gives an empty plan"):
    forAll(
      ModelGen.hostName.flatMap(h => PlanGen.registrations(h).map(h -> _))
    ):
      case (h, desired) =>
        val state = AgentState(
          desired.map(PlanGen.serviceOf),
          desired.flatMap(r => r.check.map(PlanGen.agentCheckFor(r.id, _)))
        )
        assertEquals(Planner.plan(h, desired, state), Plan(Nil, Nil))

  property("deregistrations are sorted by ID and only desired when replaced"):
    forAll(scenario):
      case (h, desired, state, _) =>
        val result = Planner.plan(h, desired, state)
        assertEquals(
          result.deregister.map(_.value),
          result.deregister.map(_.value).sorted
        )
        assert(
          result.deregister.forall(id => !desired.exists(_.id == id) || result.register.exists(_.id == id))
        )
        assertEquals(
          result.register,
          desired.filter(r => result.register.contains(r))
        )
end PlannerSuite
