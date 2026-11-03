package com.dwolla.nomenclator.consul

import com.dwolla.nomenclator.gen.ModelGen
import com.dwolla.nomenclator.model.*
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

class OwnershipSuite extends ScalaCheckSuite:
  private val host = HostName.from("ip-10-0-0-1").fold(fail(_), identity)
  private def owns(id: String) = Ownership.owns(host, ServiceId(id))

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
    assert(Ownership.owns(dotted, ServiceId("a.b:web:80")))
    assert(!Ownership.owns(dotted, ServiceId("axb:web:80")))

  property("every ID built from the host, a name, and a port is owned"):
    import ModelGen.given
    forAll: (h: HostName, name: DockerName, port: HostPort) =>
      Ownership.owns(
        h,
        ServiceId(s"${h.value}:${name.value}:${port.value.value}")
      )
end OwnershipSuite
