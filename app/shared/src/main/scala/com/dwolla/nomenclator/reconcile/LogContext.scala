package com.dwolla.nomenclator.reconcile

import com.dwolla.nomenclator.consul.AgentService
import com.dwolla.nomenclator.registration.Registration

/** How a service appears in the structured context of a log line.
  */
private[reconcile] object LogContext:
  def of(r: Registration): Map[String, String] =
    Map(
      "service_id" -> r.id.value,
      "service_name" -> r.name.value,
      "address" -> r.address,
      "port" -> r.port.value.value.toString
    )

  def of(s: AgentService): Map[String, String] =
    Map(
      "service_id" -> s.id.value,
      "service_name" -> s.name.value,
      "address" -> s.address,
      "port" -> s.port.toString
    )
end LogContext
