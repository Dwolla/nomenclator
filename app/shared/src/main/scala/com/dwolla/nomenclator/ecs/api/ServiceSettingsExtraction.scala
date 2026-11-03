package com.dwolla.nomenclator.ecs.api

import cats.syntax.all.*
import com.amazonaws.ecs.{ContainerDefinition, DescribeTaskDefinitionResponse}
import com.dwolla.nomenclator.model.ContainerName
import com.dwolla.nomenclator.settings.ContainerSettings

/** Reduces an ECS task definition to the settings Nomenclator may read: the
  * `SERVICE_*` environment entries and Docker labels of each named container.
  * Everything else, which may include secrets, is dropped here.
  */
object ServiceSettingsExtraction:
  def apply(response: DescribeTaskDefinitionResponse): Map[ContainerName, ContainerSettings] =
    response.taskDefinition
      .flatMap(_.containerDefinitions)
      .getOrElse(Nil)
      .flatMap(container => container.name.map(name => ContainerName(name) -> settingsOf(container)))
      .toMap

  private def settingsOf(container: ContainerDefinition): ContainerSettings =
    ContainerSettings.fromRaw(
      // The last occurrence of a repeated name wins.
      environment = container.environment
        .getOrElse(Nil)
        .mapFilter(pair => (pair.name, pair.value).tupled)
        .toMap,
      labels = container.dockerLabels.getOrElse(Map.empty)
    )
end ServiceSettingsExtraction
