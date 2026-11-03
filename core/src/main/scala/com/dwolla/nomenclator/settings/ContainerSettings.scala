package com.dwolla.nomenclator.settings

/** The raw `SERVICE_*` entries of a container's environment and labels, with
  * values verbatim (including empty ones).
  */
final case class ContainerSettings(environment: Map[String, String], labels: Map[String, String])

object ContainerSettings:
  private[settings] val prefix = "SERVICE_"

  /** Drops every entry whose key does not start with exactly `SERVICE_` (the
    * match is case-sensitive, as in registrator).
    */
  def fromRaw(environment: Map[String, String], labels: Map[String, String]): ContainerSettings =
    ContainerSettings(
      onlyServiceEntries(environment),
      onlyServiceEntries(labels)
    )

  private def onlyServiceEntries(entries: Map[String, String]): Map[String, String] =
    entries.filter((key, _) => key.startsWith(prefix))
end ContainerSettings
