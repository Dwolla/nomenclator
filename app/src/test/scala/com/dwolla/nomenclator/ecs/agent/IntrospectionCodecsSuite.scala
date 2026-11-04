package com.dwolla.nomenclator.ecs.agent

import com.dwolla.nomenclator.ecs.*
import com.dwolla.nomenclator.model.*
import io.circe.Json
import io.circe.literal.*
import munit.FunSuite

class IntrospectionCodecsSuite extends FunSuite:
  private def task(
                    known: String = "RUNNING",
                    desired: String = "RUNNING",
                    version: String = "3",
                    containers: Json = Json.arr()
  ): Json =
    json"""{"Tasks":[{"Arn":"arn:aws:ecs:us-east-1:123456789012:task/example/dd","KnownStatus":$known,"DesiredStatus":$desired,"Family":"foo-service","Version":$version,"Containers":$containers}]}"""

  private def container(dockerName: String, ports: Json): Json =
    json"""{"Name":"web","DockerName":$dockerName,"Image":"foo-service:1","Ports":$ports}"""

  private def decode(json: Json) =
    json.as(using IntrospectionCodecs.tasks)

  private def portsOf(ports: Json): List[(Int, Int, Protocol)] =
    decode(task(containers = Json.arr(container("web", ports)))).fold(
      throw _,
      _.flatMap(_.containers)
        .flatMap(_.ports)
        .map(b => (b.containerPort.value.value, b.hostPort.value.value, b.protocol))
    )

  test("a leading slash on the Docker name is stripped"):
    val names = decode(
      task(containers =
        Json.arr(
          container("/ecs-foo-1-web-a", Json.arr()),
          container("ecs-foo-1-web-b", Json.arr())
        )
      )
    ).fold(throw _, _.flatMap(_.containers).map(_.dockerName.value))
    assertEquals(names, List("ecs-foo-1-web-a", "ecs-foo-1-web-b"))
    names.foreach(n => assert(n.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]+"), s"$n is not a valid name"))

  test("IPv4 and IPv6 bindings of one port collapse, keeping input order"):
    assertEquals(
      portsOf(
        json"""[
          {"ContainerPort":9001,"Protocol":"udp","HostPort":32771},
          {"ContainerPort":8080,"Protocol":"tcp","HostPort":32768},
          {"ContainerPort":9001,"Protocol":"udp","HostPort":32771},
          {"ContainerPort":8080,"Protocol":"udp","HostPort":32768}
        ]"""
      ),
      List(
        (9001, 32771, Protocol.Udp),
        (8080, 32768, Protocol.Tcp),
        (8080, 32768, Protocol.Udp)
      )
    )

  test("a binding without a host port is dropped"):
    assertEquals(
      portsOf(json"""[{"ContainerPort":8080,"Protocol":"tcp"}]"""),
      Nil
    )

  test("a binding with host port 0 is dropped"):
    assertEquals(
      portsOf(json"""[{"ContainerPort":8080,"Protocol":"tcp","HostPort":0}]"""),
      Nil
    )

  test("a missing protocol means tcp"):
    assertEquals(
      portsOf(json"""[{"ContainerPort":8080,"HostPort":32768}]"""),
      List((8080, 32768, Protocol.Tcp))
    )

  test("a host port above 65535 is dropped"):
    assertEquals(
      portsOf(
        json"""[{"ContainerPort":8080,"Protocol":"tcp","HostPort":65536}]"""
      ),
      Nil
    )

  test("a negative host port is dropped"):
    assertEquals(
      portsOf(
        json"""[{"ContainerPort":8080,"Protocol":"tcp","HostPort":-1}]"""
      ),
      Nil
    )

  test("a container port above 65535 is dropped"):
    assertEquals(
      portsOf(
        json"""[{"ContainerPort":70000,"Protocol":"tcp","HostPort":32768}]"""
      ),
      Nil
    )

  test("a negative container port is dropped"):
    assertEquals(
      portsOf(
        json"""[{"ContainerPort":-5,"Protocol":"tcp","HostPort":32768}]"""
      ),
      Nil
    )

  test("a port too large for an Int is dropped"):
    assertEquals(
      portsOf(
        json"""[{"ContainerPort":8080,"Protocol":"tcp","HostPort":99999999999}]"""
      ),
      Nil
    )

  test("a binding with an unknown protocol is dropped"):
    assertEquals(
      portsOf(
        json"""[{"ContainerPort":8080,"Protocol":"sctp","HostPort":32768}]"""
      ),
      Nil
    )

  test("a dropped binding leaves the valid ones in place"):
    assertEquals(
      portsOf(
        json"""[
          {"ContainerPort":8080,"Protocol":"tcp","HostPort":70000},
          {"ContainerPort":8081,"Protocol":"tcp","HostPort":32768}
        ]"""
      ),
      List((8081, 32768, Protocol.Tcp))
    )

  private def withNetworks(networks: Json): Json =
    container(
      "web",
      json"""[{"ContainerPort":8080,"Protocol":"tcp","HostPort":8080}]"""
    )
      .deepMerge(Json.obj("Networks" -> networks))

  private def decodeContainer(c: Json) =
    decode(task(containers = Json.arr(c))).map(_.flatMap(_.containers))

  // In awsvpc mode the container listens on its own ENI, and the agent reports
  // HostPort = ContainerPort though nothing listens on the host there.
  test("an awsvpc container's bindings are dropped"):
    assertEquals(
      decodeContainer(
        withNetworks(
          json"""[{"NetworkMode":"awsvpc","IPv4Addresses":["10.0.1.5"]}]"""
        )
      ).map(_.map(_.ports)),
      Right(List(Nil))
    )

  test(
    "a container whose Networks is null or names another mode keeps its bindings"
  ):
    List(
      json"null",
      json"[]",
      json"""[{"NetworkMode":"bridge","IPv4Addresses":["172.17.0.2"]}]"""
    ).foreach: networks =>
      assertEquals(
        decodeContainer(withNetworks(networks)).map(_.flatMap(_.ports).size),
        Right(1),
        s"Networks: $networks"
      )

  test("malformed Networks fail the decode"):
    List(
      json""""awsvpc"""",
      json"""["awsvpc"]""",
      json"""[{"IPv4Addresses":["10.0.1.5"]}]""",
      json"""[{"NetworkMode":null}]""",
      json"""[{"NetworkMode":5}]"""
    ).foreach: networks =>
      assert(
        decodeContainer(withNetworks(networks)).isLeft,
        s"expected $networks to fail"
      )

  test("unmapped statuses become Other and never fail decoding"):
    val tasks = decode(task(known = "DEPROVISIONING", desired = ""))
      .fold(throw _, identity)
    assertEquals(
      tasks.map(t => (t.knownStatus, t.desiredStatus)),
      List((TaskStatus.Other("DEPROVISIONING"), TaskStatus.Other("")))
    )

  test("a missing DesiredStatus becomes Other and never fails decoding"):
    val body =
      json"""{"Tasks":[{"Arn":"a","KnownStatus":"STOPPED","Family":"f","Version":"1","Containers":[]}]}"""
    assertEquals(
      decode(body).map(_.map(_.desiredStatus)),
      Right(List(TaskStatus.Other("")))
    )

  test("an unparseable Version fails the whole decode"):
    assert(decode(task(version = "three")).isLeft)

  private val taskWithoutContainers =
    json"""{"Arn":"a","KnownStatus":"RUNNING","DesiredStatus":"RUNNING","Family":"f","Version":"1"}"""

  test("a task whose Containers is null or empty has no containers"):
    List(json"null", json"[]").foreach: containers =>
      assertEquals(
        decode(
          Json.obj(
            "Tasks" -> Json.arr(
              taskWithoutContainers.deepMerge(
                Json.obj("Containers" -> containers)
              )
            )
          )
        ).map(_.map(_.containers)),
        Right(List(Nil))
      )

  test("a task without the Containers key fails the decode"):
    assert(decode(Json.obj("Tasks" -> Json.arr(taskWithoutContainers))).isLeft)

  test("an empty or null Tasks means no tasks"):
    assertEquals(decode(json"""{"Tasks":[]}"""), Right(Nil))
    assertEquals(decode(json"""{"Tasks":null}"""), Right(Nil))

  test("a response that isn't a Tasks envelope fails the decode"):
    List(json"{}", json"[]", json"""{"tasks":[]}""").foreach: body =>
      assert(decode(body).isLeft, s"expected $body to fail")

  test("malformed port entries fail the decode"):
    List(
      json"""[{"ContainerPort":8080,"Protocol":"tcp","HostPort":"32768"}]""",
      json"""[{"ContainerPort":8080,"Protocol":6,"HostPort":32768}]""",
      json"""[{"Protocol":"tcp","HostPort":32768}]""",
      json"""[{"ContainerPort":null,"Protocol":"tcp","HostPort":32768}]""",
      json"""["8080"]"""
    ).foreach: ports =>
      assert(
        decode(task(containers = Json.arr(container("web", ports)))).isLeft,
        s"expected $ports to fail"
      )

  test("a null HostPort is dropped, not an error"):
    assertEquals(
      portsOf(
        json"""[{"ContainerPort":8080,"Protocol":"tcp","HostPort":null}]"""
      ),
      Nil
    )

  test("metadata without a container instance ARN fails"):
    assert(
      json"""{"Cluster":"example","ContainerInstanceArn":null}"""
        .as(using IntrospectionCodecs.metadata)
        .isLeft
    )
    assert(
      json"""{"Cluster":"example","ContainerInstanceArn":""}"""
        .as(using IntrospectionCodecs.metadata)
        .isLeft
    )
end IntrospectionCodecsSuite
