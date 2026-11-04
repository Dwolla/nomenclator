package com.dwolla.nomenclator.integration

import com.dwolla.nomenclator.model.{HostName, ServiceAddress, ServiceId}

/** What the introspection fixtures served by [[FakeEcsAgent]] make a host named
  * [[hostName]] register, by service ID: the one place the expected IDs are
  * spelled out.
  */
object FixtureServices:
  val hostName: HostName =
    HostName
      .from("test-host")
      .fold(msg => throw new AssertionError(msg), identity)
  val address: ServiceAddress = ServiceAddress("10.0.0.1")

  val fooWeb: ServiceId =
    ServiceId("test-host:ecs-foo-service-3-web-a1b2c3d4e5f6a7b8c9d0:8080")
  val barTcp: ServiceId =
    ServiceId("test-host:ecs-bar-worker-12-worker-0a1b2c3d4e5f6a7b8c9d:9000")
  val barUdp: ServiceId =
    ServiceId(
      "test-host:ecs-bar-worker-12-worker-0a1b2c3d4e5f6a7b8c9d:9001:udp"
    )

  val expectedIds: Set[ServiceId] = Set(fooWeb, barTcp, barUdp)
end FixtureServices
