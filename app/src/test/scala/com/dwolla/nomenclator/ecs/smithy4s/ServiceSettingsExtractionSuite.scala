package com.dwolla.nomenclator.ecs.smithy4s

import com.amazonaws.ecs.*
import com.dwolla.nomenclator.ecs.model.ContainerName
import com.dwolla.nomenclator.registration.settings.ContainerSettings
import munit.FunSuite

class ServiceSettingsExtractionSuite extends FunSuite:
  private def kv(name: String, value: String) =
    KeyValuePair(name = Some(name), value = Some(value))

  private def response(containers: ContainerDefinition*) =
    DescribeTaskDefinitionResponse(taskDefinition =
      Some(TaskDefinition(containerDefinitions = Some(containers.toList)))
    )

  test("keeps only SERVICE_ keys from the environment"):
    val result = ServiceSettingsExtraction(
      response(
        ContainerDefinition(
          name = Some("web"),
          environment = Some(
            List(kv("SERVICE_NAME", "a"), kv("DB_PASSWORD", "hunter2-test"))
          )
        )
      )
    )

    assertEquals(
      result,
      Map(
        ContainerName("web") ->
          ContainerSettings.fromRaw(Map("SERVICE_NAME" -> "a"), Map.empty)
      )
    )

  test("a value outside SERVICE_ keys appears nowhere in the result"):
    val result = ServiceSettingsExtraction(
      response(
        ContainerDefinition(
          name = Some("web"),
          environment = Some(
            List(kv("SERVICE_NAME", "a"), kv("DB_PASSWORD", "hunter2-test"))
          ),
          dockerLabels = Some(Map("com.example.secret" -> "hunter2-test"))
        )
      )
    )

    assert(!result.toString.contains("hunter2-test"), result.toString)

  test("the last occurrence of a repeated environment name wins"):
    val result = ServiceSettingsExtraction(
      response(
        ContainerDefinition(
          name = Some("web"),
          environment = Some(List(kv("SERVICE_NAME", "first"), kv("SERVICE_NAME", "last")))
        )
      )
    )

    assertEquals(
      result(ContainerName("web")),
      ContainerSettings.fromRaw(Map("SERVICE_NAME" -> "last"), Map.empty)
    )

  test("docker labels are labels, not environment"):
    val result = ServiceSettingsExtraction(
      response(
        ContainerDefinition(
          name = Some("web"),
          dockerLabels = Some(Map("SERVICE_TAGS" -> "x", "other.label" -> "y"))
        )
      )
    )

    assertEquals(
      result(ContainerName("web")),
      ContainerSettings.fromRaw(Map.empty, Map("SERVICE_TAGS" -> "x"))
    )

  test("container definitions without a name are skipped"):
    val result = ServiceSettingsExtraction(
      response(
        ContainerDefinition(environment = Some(List(kv("SERVICE_NAME", "a")))),
        ContainerDefinition(name = Some("web"))
      )
    )

    assertEquals(result.keySet, Set(ContainerName("web")))

  test("a response without a task definition has no containers"):
    assertEquals(
      ServiceSettingsExtraction(DescribeTaskDefinitionResponse()),
      Map.empty
    )
end ServiceSettingsExtractionSuite
