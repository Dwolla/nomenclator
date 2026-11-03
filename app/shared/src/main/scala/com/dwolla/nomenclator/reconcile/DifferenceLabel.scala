package com.dwolla.nomenclator.reconcile

import com.dwolla.nomenclator.plan.Difference

/** How a [[Difference]] is named in logs and metrics. `values` lists every
  * name, so a metric can report a kind of difference that has none as zero.
  */
enum DifferenceLabel(val value: String):
  case Missing extends DifferenceLabel("missing")
  case Extra extends DifferenceLabel("extra")
  case Different extends DifferenceLabel("different")

object DifferenceLabel:
  def of(difference: Difference): DifferenceLabel = difference match
    case Difference.Missing(_)         => Missing
    case Difference.Extra(_)           => Extra
    case Difference.Different(_, _, _) => Different
