package com.dwolla.nomenclator.plan

import cats.data.NonEmptyList
import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.registration.Registration

/** Compares what Nomenclator would register with everything on the agent,
  * registrator's entries included, so shadow mode can show what a cutover would
  * change. Matching is one-to-one: each agent service answers at most one
  * desired registration, preferring the one whose tags are identical, so tcp
  * and udp on the same port don't both claim the same entry. An unmatched agent
  * service is reported as extra only if its ID has the shape registrator gave
  * IDs, from any host: Consul's own service and hand-written registrations are
  * neither registrator's nor Nomenclator's to replace.
  */
object ShadowReport:
  def compare(desired: List[Registration], actual: AgentState): List[Difference] =
    val checks = CheckComparison.registrationChecks(actual)
    val (unmatched, differences) =
      desired.foldLeft((actual.services, List.empty[List[Difference]])):
        case ((remaining, found), r) =>
          val candidates = remaining.filter(matches(r, _))
          candidates
            .find(_.tags == r.tags)
            .orElse(candidates.headOption) match
            case None    => (remaining, List(Difference.Missing(r)) :: found)
            case Some(s) =>
              val fields = List(
                Option.when(s.tags != r.tags)("tags"),
                Option.unless(
                  CheckComparison.unchanged(r.check, checks.get(s.id))
                )("check")
              ).flatten
              (
                remaining.filterNot(_.id == s.id),
                NonEmptyList
                  .fromList(fields)
                  .map(Difference.Different(r, s, _))
                  .toList :: found
              )
          end match
    differences.reverse.flatten ++ unmatched
      .filter(s => ServiceIds.isRegistratorShaped(s.id))
      .map(Difference.Extra(_))
  end compare

  private def matches(r: Registration, s: AgentService): Boolean =
    s.name == r.name && s.address == r.address && s.port == r.port.value.value
end ShadowReport
