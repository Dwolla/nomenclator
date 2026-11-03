package com.dwolla.nomenclator.consul

import com.dwolla.nomenclator.model.*

import java.util.regex.Pattern

/** Which Consul services belong to this host's Nomenclator. Registrator and
  * everything else on the agent are left alone.
  */
object Ownership:

  /** True iff the ID has the host-shaped form `host:name:port` or
    * `host:name:port:udp`, with this host's name first. This is the rule
    * registrator's `-cleanup` used to recognize its own services, so what it
    * registered before a cutover is recognized afterwards.
    */
  def owns(host: HostName, id: ServiceId): Boolean =
    Pattern
      .compile(
        s"${Pattern.quote(host.value)}:[a-zA-Z0-9][a-zA-Z0-9_.-]+:[0-9]+(:udp)?"
      )
      .matcher(id.value)
      .matches()
end Ownership
