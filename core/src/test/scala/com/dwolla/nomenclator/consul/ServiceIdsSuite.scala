package com.dwolla.nomenclator.consul

import com.dwolla.nomenclator.gen.ModelGen
import com.dwolla.nomenclator.gen.ModelGen.given
import com.dwolla.nomenclator.model.*
import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

class ServiceIdsSuite extends ScalaCheckSuite:
  private val host = HostName.from("ip-10-0-0-1").fold(fail(_), identity)
  private def owns(id: String) = ServiceIds.owns(host)(ServiceId(id))

  private def port(n: Int): ContainerPort =
    ContainerPort.from(n).fold(fail(_), identity)

  test("an ID is the host, the Docker name, and the container port"):
    assertEquals(
      ServiceIds.of(host, DockerName("ecs-web-1"), port(8080), Protocol.Tcp),
      ServiceId("ip-10-0-0-1:ecs-web-1:8080")
    )

  test("a UDP port's ID ends in :udp"):
    assertEquals(
      ServiceIds.of(host, DockerName("ecs-web-1"), port(53), Protocol.Udp),
      ServiceId("ip-10-0-0-1:ecs-web-1:53:udp")
    )

  test("a registration's check is service:<id>"):
    assertEquals(
      ServiceIds.checkId(ServiceId("ip-10-0-0-1:ecs-web-1:8080")),
      "service:ip-10-0-0-1:ecs-web-1:8080"
    )

  test("a host-shaped TCP ID is owned"):
    assert(owns("ip-10-0-0-1:web:80"))

  test("a host-shaped UDP ID is owned"):
    assert(owns("ip-10-0-0-1:web:80:udp"))

  test("an ID that starts with another host is not owned"):
    assert(!owns("abc123:web:80"))

  test("an ID without separators is not owned"):
    assert(!owns("ip-10-0-0-1"))

  test("two parts, or four parts not ending in udp, are not owned"):
    assert(!owns("ip-10-0-0-1:web"))
    assert(!owns("ip-10-0-0-1:web:80:tcp"))
    assert(!owns("ip-10-0-0-1:web:80:udp:extra"))
    assert(!owns("ip-10-0-0-1:web:80:"))

  test("a host name that merely prefixes the first part is not owned"):
    assert(!owns("ip-10-0-0-10:web:80"))

  test(
    "an empty name, an empty port, a non-numeric port, or a bad name is not owned"
  ):
    assert(!owns("ip-10-0-0-1:x:"))
    assert(!owns("ip-10-0-0-1::80"))
    assert(!owns("ip-10-0-0-1:web:http"))
    assert(!owns("ip-10-0-0-1:a!b:80"))

  test(
    "a one-character name or a name starting with punctuation is not owned"
  ):
    assert(!owns("ip-10-0-0-1:x:80"))
    assert(!owns("ip-10-0-0-1:-web:80"))

  test("name characters registrator allows are owned"):
    assert(owns("ip-10-0-0-1:ecs_foo-service.3-web:80"))

  test("a host name's regex metacharacters are taken literally"):
    val dotted = HostName.from("a.b").fold(fail(_), identity)
    assert(ServiceIds.owns(dotted)(ServiceId("a.b:web:80")))
    assert(!ServiceIds.owns(dotted)(ServiceId("axb:web:80")))

  test("a Docker name that prefixes another's does not claim its IDs"):
    val short = DockerName("ecs-foo")
    val long = DockerName("ecs-foo-bar")
    assert(
      !ServiceIds.belongsTo(host, short)(
        ServiceIds.of(host, long, port(80), Protocol.Tcp)
      )
    )
    assert(
      !ServiceIds.belongsTo(host, long)(
        ServiceIds.of(host, short, port(80), Protocol.Tcp)
      )
    )

  test("registrator's shape is recognized from any host"):
    assert(ServiceIds.isRegistratorShaped(ServiceId("abc123:web:80")))
    assert(ServiceIds.isRegistratorShaped(ServiceId("abc123:web:80:udp")))
    assert(!ServiceIds.isRegistratorShaped(ServiceId("consul")))
    assert(!ServiceIds.isRegistratorShaped(ServiceId("foo-service")))
    assert(!ServiceIds.isRegistratorShaped(ServiceId(":web:80")))
    assert(!ServiceIds.isRegistratorShaped(ServiceId("abc123:web:80:tcp")))

  property(
    "every ID built for a host is owned by it and has registrator's shape"
  ):
    forAll: (h: HostName, d: DockerName, p: ContainerPort, protocol: Protocol) =>
      val id = ServiceIds.of(h, d, p, protocol)
      ServiceIds.owns(h)(id) && ServiceIds.isRegistratorShaped(id)

  property("an ID belongs to the Docker name it was built from"):
    forAll: (h: HostName, d: DockerName, p: ContainerPort, protocol: Protocol) =>
      ServiceIds.belongsTo(h, d)(ServiceIds.of(h, d, p, protocol))

  property("an ID never belongs to another Docker name"):
    forAll:
      (
          h: HostName,
          d1: DockerName,
          d2: DockerName,
          p: ContainerPort,
          protocol: Protocol
      ) =>
        (d1 != d2) ==> !ServiceIds.belongsTo(h, d1)(
          ServiceIds.of(h, d2, p, protocol)
        )

  property("an ID built for one host is not owned by another"):
    forAll:
      (
          h1: HostName,
          h2: HostName,
          d: DockerName,
          p: ContainerPort,
          protocol: Protocol
      ) => (h1 != h2) ==> !ServiceIds.owns(h1)(ServiceIds.of(h2, d, p, protocol))

  property("a prefix-sharing Docker name never claims the longer name's IDs"):
    forAll(ModelGen.dockerName, ModelGen.dockerName): (d, suffix) =>
      val longer = DockerName(s"${d.value}-${suffix.value}")
      forAll: (h: HostName, p: ContainerPort, protocol: Protocol) =>
        !ServiceIds.belongsTo(h, d)(ServiceIds.of(h, longer, p, protocol))
end ServiceIdsSuite
