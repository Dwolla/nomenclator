package com.dwolla.nomenclator.consul

import com.dwolla.nomenclator.model.*

import java.util.regex.Pattern

/** The service IDs Nomenclator gives container ports, which have the shape
  * registrator gave them: `host:dockerName:port`, with `:udp` appended for a
  * UDP port. Building them and recognizing them both live here.
  *
  * The host part is what tells this host's services from everything else on the
  * agent, which is left alone. Registrator ran in bridge mode without a
  * hostname, so its IDs start with its own container's ID instead of a host's
  * name, and this host never owns them.
  */
object ServiceIds:
  private val portSuffix = ":[a-zA-Z0-9][a-zA-Z0-9_.-]+:[0-9]+(:udp)?"
  private val registratorShape = Pattern.compile(s"[^:]+$portSuffix")

  def of(host: HostName, dockerName: DockerName, containerPort: ContainerPort, protocol: Protocol): ServiceId =
    val protocolSuffix = if (protocol == Protocol.Udp) ":udp" else ""
    ServiceId(
      s"${host.value}:${dockerName.value}:${containerPort.value.value}$protocolSuffix"
    )

  /** Whether an ID is this host's: `host:name:port` or `host:name:port:udp`,
    * with this host's name first. This is the rule registrator's `-cleanup`
    * used to recognize its own services. The pattern is compiled once, when the
    * host is given, so apply the result to every ID that needs checking.
    */
  def owns(host: HostName): ServiceId => Boolean =
    val pattern = Pattern.compile(s"${Pattern.quote(host.value)}$portSuffix")
    id => pattern.matcher(id.value).matches()

  /** Whether an ID was built for one of the container's ports. Docker names
    * can't contain `:`, so a name that prefixes another's never claims its IDs.
    */
  def belongsTo(host: HostName, dockerName: DockerName)(id: ServiceId): Boolean =
    id.value.startsWith(s"${host.value}:${dockerName.value}:")

  /** Whether an ID has the shape registrator gave IDs, on any host.
    */
  def isRegistratorShaped(id: ServiceId): Boolean =
    registratorShape.matcher(id.value).matches()

  /** The ID Consul gives the check registered with a service: `service:<id>`.
    */
  def checkId(id: ServiceId): CheckId = CheckId(s"service:${id.value}")
end ServiceIds
