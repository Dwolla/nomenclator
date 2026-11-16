package com.dwolla.nomenclator.registration

import com.dwolla.nomenclator.consul.model.*
import com.dwolla.nomenclator.ecs.model.*
import com.dwolla.nomenclator.registration.settings.ServiceSettings

/** Registrator's rules for a service's name and tags.
  */
object ServiceNaming:

  /** `SERVICE_NAME`, else the image's base name. In a multi-port container the
    * container port is appended, unless the name was set for this port
    * specifically.
    */
  def name(settings: ServiceSettings, image: ImageName, port: ContainerPort, isGroup: Boolean): ServiceName =
    val base = settings.get("name").getOrElse(imageBaseName(image.value))
    val full =
      if (isGroup && !settings.isPortSpecific("name"))
        s"$base-${port.value.value}"
      else base
    ServiceName(full)

  /** The container's `SERVICE_TAGS`, then the global tags, then `udp` for UDP.
    * Nothing is trimmed or deduplicated, and empty elements are kept.
    */
  def tags(settings: ServiceSettings, globalTags: List[Tag], protocol: Protocol): List[Tag] =
    settings.get("tags").toList.flatMap(_.split(",", -1).toList.map(Tag(_))) ++
      globalTags ++
      (if (protocol == Protocol.Udp) List(Tag("udp")) else Nil)

  /** Go's `strings.Split(path.Base(image), ":")[0]`.
    */
  def imageBaseName(image: String): String =
    val trimmed = image.reverse.dropWhile(_ == '/').reverse
    val base =
      if (image.isEmpty) "."
      else if (trimmed.isEmpty) "/"
      else trimmed.substring(trimmed.lastIndexOf('/') + 1)
    base.takeWhile(_ != ':')
end ServiceNaming
