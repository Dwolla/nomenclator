package com.dwolla.nomenclator.registration

import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.settings.{ContainerSettings, ServiceSettings}
import munit.FunSuite

class ServiceNamingSuite extends FunSuite:
  private val port8080 = ContainerPort.from(8080).fold(fail(_), identity)
  private val image = ImageName(
    "registry.example.com:5000/team/foo-service:abc"
  )

  private def settings(entries: (String, String)*) =
    ServiceSettings.forPort(
      ContainerSettings.fromRaw(entries.toMap, Map.empty),
      port8080
    )

  test("the default name is the image base name"):
    assertEquals(
      ServiceNaming.name(settings(), image, port8080, isGroup = false),
      ServiceName("foo-service")
    )

  test("SERVICE_NAME overrides the image name"):
    assertEquals(
      ServiceNaming.name(
        settings("SERVICE_NAME" -> "foo"),
        image,
        port8080,
        isGroup = false
      ),
      ServiceName("foo")
    )

  test("a generic name is suffixed with the port in a group"):
    assertEquals(
      ServiceNaming.name(
        settings("SERVICE_NAME" -> "foo"),
        image,
        port8080,
        isGroup = true
      ),
      ServiceName("foo-8080")
    )

  test("a port-specific name is not suffixed in a group"):
    assertEquals(
      ServiceNaming.name(
        settings("SERVICE_8080_NAME" -> "api"),
        image,
        port8080,
        isGroup = true
      ),
      ServiceName("api")
    )

  test("tags are the container's, then the global ones, then udp"):
    assertEquals(
      ServiceNaming.tags(
        settings("SERVICE_TAGS" -> "a,,b, c"),
        List(Tag("g")),
        Protocol.Udp
      ),
      List(Tag("a"), Tag(""), Tag("b"), Tag(" c"), Tag("g"), Tag("udp"))
    )

  test("a trailing comma keeps its empty tag"):
    assertEquals(
      ServiceNaming.tags(settings("SERVICE_TAGS" -> "a,"), Nil, Protocol.Tcp),
      List(Tag("a"), Tag(""))
    )

  test("no tags settings and no global tags give no tags"):
    assertEquals(ServiceNaming.tags(settings(), Nil, Protocol.Tcp), Nil)

  List(
    "redis" -> "redis",
    "gliderlabs/foobar:1.2" -> "foobar",
    "registry:5000/team/app:3" -> "app",
    "registry:5000/app" -> "app",
    "app@sha256:abc" -> "app@sha256",
    "sha256:abc" -> "sha256",
    "" -> "."
  ).foreach: (image, expected) =>
    test(s"image base name of '$image' is '$expected'"):
      assertEquals(ServiceNaming.imageBaseName(image), expected)
end ServiceNamingSuite
