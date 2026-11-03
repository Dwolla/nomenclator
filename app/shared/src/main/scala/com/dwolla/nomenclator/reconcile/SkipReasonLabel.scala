package com.dwolla.nomenclator.reconcile

import com.dwolla.nomenclator.registration.SkipReason

/** How a [[SkipReason]] is named in logs and metrics. `values` lists every
  * name, so a metric can report a reason no container has as zero.
  */
enum SkipReasonLabel(val value: String):
  case Ignored extends SkipReasonLabel("ignored")
  case ScriptCheck extends SkipReasonLabel("script_check")
  case TtlCheck extends SkipReasonLabel("ttl_check")
  case NoHostPort extends SkipReasonLabel("no_host_port")
  case InvalidSettings extends SkipReasonLabel("invalid_settings")

object SkipReasonLabel:
  def of(reason: SkipReason): SkipReasonLabel = reason match
    case SkipReason.Ignored            => Ignored
    case SkipReason.ScriptCheck        => ScriptCheck
    case SkipReason.TtlCheck           => TtlCheck
    case SkipReason.NoHostPort         => NoHostPort
    case SkipReason.InvalidSettings(_) => InvalidSettings
