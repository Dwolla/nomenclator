package com.dwolla.nomenclator.settings

import cats.syntax.all.*
import com.dwolla.nomenclator.model.ContainerPort

import java.util.Locale

/** The settings that apply to one container port, resolved with registrator
  * v7's precedence.
  *
  * @param values
  *   attribute -> value, keyed by the lowercased remainder after `SERVICE_`
  * @param fromPort
  *   attributes set by a `SERVICE_<port>_<attribute>` entry for this port, even
  *   when its value is empty
  */
final case class ServiceSettings(values: Map[String, String], fromPort: Set[String]):

  /** The attribute's value, or `None` when unset or empty.
    */
  def get(key: String): Option[String] =
    values.get(key).filter(_.nonEmpty)

  def isPortSpecific(key: String): Boolean =
    fromPort.contains(key)

  /** Any non-empty `ignore`, `false` and `0` included, ignores the port.
    */
  def ignored: Boolean =
    get("ignore").isDefined
end ServiceSettings

object ServiceSettings:
  private val GoInteger = "[+-]?[0-9]+".r

  /** Registrator's `serviceMetaData`. Registrator appends labels after
    * environment entries and walks them in an order that is random for labels;
    * both are walked here in sorted key order, so a later key wins
    * deterministically.
    */
  def forPort(settings: ContainerSettings, port: ContainerPort): ServiceSettings =
    val exposedPort = port.value.value.toString
    val entries = sorted(settings.environment) ++ sorted(settings.labels)

    entries.foldLeft(ServiceSettings(Map.empty, Set.empty)):
      case (acc, (rawKey, value)) =>
        if (!rawKey.startsWith(ContainerSettings.prefix)) acc
        else {
          val key = rawKey
            .stripPrefix(ContainerSettings.prefix)
            .toLowerCase(Locale.ROOT)
          if (acc.fromPort.contains(key)) acc
          else
            portAndAttribute(key) match
              case Some((keyPort, attribute)) =>
                if (keyPort == exposedPort)
                  ServiceSettings(
                    acc.values.updated(attribute, value),
                    acc.fromPort + attribute
                  )
                else acc
              case None => acc.copy(values = acc.values.updated(key, value))
        }
  end forPort

  private def sorted(entries: Map[String, String]): List[(String, String)] =
    entries.toList.sortBy(_._1)

  /** Go's `SplitN(key, "_", 2)` with `Atoi` on the first part: a port-specific
    * key has an integer (signed numbers included) before the first `_`.
    */
  private def portAndAttribute(key: String): Option[(String, String)] =
    key.split("_", 2) match
      case Array(number, attribute) if isGoInt(number) =>
        (number, attribute).some
      case _ => None

  private def isGoInt(s: String): Boolean =
    GoInteger.matches(s) && s.toLongOption.isDefined
end ServiceSettings
