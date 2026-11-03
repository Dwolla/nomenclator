package com.dwolla.nomenclator.plan

import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.gen.PlanGen
import com.dwolla.nomenclator.model.ServiceId
import com.dwolla.nomenclator.registration.Check
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

import scala.concurrent.duration.*

class CheckComparisonSuite extends ScalaCheckSuite:
  private val http = Check.Http("http://h/health", 10.seconds, Some(2.seconds))
  private val tcp = Check.Tcp("h:80", 1.minute, None)

  private def matches(c: Check, d: CheckDefinition) =
    CheckComparison.matches(c, d)

  property("a definition rendered from a check matches that check"):
    forAll(PlanGen.check)(c => matches(c, PlanGen.definitionOf(c)))

  test("a different HTTP URL or TCP target differs"):
    val d = PlanGen.definitionOf(http)
    assert(!matches(http, d.copy(http = Some("http://h/other"))))
    val t = PlanGen.definitionOf(tcp)
    assert(!matches(tcp, t.copy(tcp = Some("h:81"))))

  test("an HTTP check does not match a TCP definition, and vice versa"):
    assert(!matches(http, PlanGen.definitionOf(tcp)))
    assert(!matches(tcp, PlanGen.definitionOf(http)))

  test("an empty string for the other kind's target counts as absent"):
    assert(
      matches(http, PlanGen.definitionOf(http).copy(tcp = Some("")))
    )
    assert(
      matches(tcp, PlanGen.definitionOf(tcp).copy(http = Some("")))
    )

  test("a different interval differs"):
    assert(
      !matches(http, PlanGen.definitionOf(http).copy(interval = Some("11s")))
    )

  test("equal durations match however the agent spells them"):
    assert(
      matches(
        tcp,
        PlanGen.definitionOf(tcp).copy(interval = Some("60s"))
      )
    )

  test("a missing or unparseable interval differs"):
    val d = PlanGen.definitionOf(http)
    assert(!matches(http, d.copy(interval = None)))
    assert(!matches(http, d.copy(interval = Some("soon"))))

  test("a different, missing, or unparseable timeout differs"):
    val d = PlanGen.definitionOf(http)
    assert(!matches(http, d.copy(timeout = Some("3s"))))
    assert(!matches(http, d.copy(timeout = None)))
    assert(!matches(http, d.copy(timeout = Some("soon"))))

  test("an absent desired timeout matches an absent or zero agent timeout"):
    val d = PlanGen.definitionOf(tcp)
    assert(matches(tcp, d.copy(timeout = None)))
    assert(matches(tcp, d.copy(timeout = Some("0s"))))
    assert(matches(tcp, d.copy(timeout = Some("0"))))
    assert(!matches(tcp, d.copy(timeout = Some("1s"))))
    assert(!matches(tcp, d.copy(timeout = Some("soon"))))

  private val id = ServiceId("h:web:80")
  private def agentCheck(d: Option[CheckDefinition]) =
    AgentCheck(PlanGen.registrationCheckId(id), Some(id), d)

  test("no desired check and no agent check is unchanged"):
    assert(CheckComparison.unchanged(None, None))

  test("an agent check that isn't desired is a change"):
    assert(!CheckComparison.unchanged(None, Some(agentCheck(None))))

  test("a desired check the agent lacks is a change"):
    assert(!CheckComparison.unchanged(Some(tcp), None))

  test("an agent check without a definition is unchanged when desired"):
    assert(CheckComparison.unchanged(Some(tcp), Some(agentCheck(None))))

  test("a reported definition decides"):
    val d = PlanGen.definitionOf(tcp)
    assert(CheckComparison.unchanged(Some(tcp), Some(agentCheck(Some(d)))))
    assert(
      !CheckComparison.unchanged(
        Some(tcp),
        Some(agentCheck(Some(d.copy(interval = Some("1s")))))
      )
    )

  test("only the check with ID service:<id> is the registration's check"):
    val own = agentCheck(None)
    val maintenance = PlanGen.maintenanceCheckFor(id)
    val other = AgentCheck("custom", Some(id), None)
    val detached = AgentCheck(PlanGen.registrationCheckId(id), None, None)
    val state = AgentState(Nil, List(maintenance, other, detached, own))
    assertEquals(CheckComparison.registrationChecks(state), Map(id -> own))
    assertEquals(
      CheckComparison.registrationChecks(
        AgentState(Nil, List(maintenance, other, detached))
      ),
      Map.empty[ServiceId, AgentCheck]
    )
end CheckComparisonSuite
