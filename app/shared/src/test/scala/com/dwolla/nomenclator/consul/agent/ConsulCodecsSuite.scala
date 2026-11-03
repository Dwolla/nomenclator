package com.dwolla.nomenclator.consul.agent

import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.registration.*
import io.circe.Json
import io.circe.literal.*
import munit.FunSuite

import scala.concurrent.duration.*

class ConsulCodecsSuite extends FunSuite:
  private val httpRegistration = Registration(
    ServiceId("ip-10-0-0-1:ecs-foo-service-3-web-a1b2:8080"),
    ServiceName("foo-service"),
    Nil,
    "10.0.0.1",
    HostPort.from(32768).fold(fail(_), identity),
    Some(Check.Http("http://10.0.0.1:32768/health", 5.seconds, Some(1.second)))
  )

  private def decodeChecks(json: Json) =
    json.as(using ConsulCodecs.agentChecks).fold(throw _, identity)

  private def decodeServices(json: Json) =
    json.as(using ConsulCodecs.agentServices).fold(throw _, identity)

  test("an HTTP registration encodes to the body registrator would send"):
    assertEquals(
      ConsulCodecs.registerBody(httpRegistration),
      json"""{"ID":"ip-10-0-0-1:ecs-foo-service-3-web-a1b2:8080","Name":"foo-service","Address":"10.0.0.1","Port":32768,"Check":{"HTTP":"http://10.0.0.1:32768/health","Interval":"5s","Timeout":"1s"}}"""
    )

  test("tags are sent when present and omitted when empty"):
    val tagged = httpRegistration.copy(tags = List(Tag("a"), Tag("")))
    assertEquals(
      ConsulCodecs.registerBody(tagged).hcursor.downField("Tags").focus,
      Some(json"""["a",""]""")
    )
    assertEquals(
      ConsulCodecs
        .registerBody(httpRegistration)
        .hcursor
        .downField("Tags")
        .focus,
      None
    )

  test("a TCP check without a timeout omits Timeout"):
    val r = httpRegistration.copy(check = Some(Check.Tcp("10.0.0.1:32768", 1.minute + 30.seconds, None)))
    assertEquals(
      ConsulCodecs.registerBody(r).hcursor.downField("Check").focus,
      Some(json"""{"TCP":"10.0.0.1:32768","Interval":"1m30s"}""")
    )

  test("an empty address, a zero port, and no check are omitted"):
    val r = httpRegistration.copy(
      address = "",
      port = HostPort.from(0).fold(fail(_), identity),
      check = None
    )
    assertEquals(
      ConsulCodecs.registerBody(r),
      json"""{"ID":"ip-10-0-0-1:ecs-foo-service-3-web-a1b2:8080","Name":"foo-service"}"""
    )

  test("services decode from the agent's map keyed by service ID"):
    val services = decodeServices(json"""{
      "h:web:80": {"ID":"h:web:80","Service":"web","Tags":["a"],"Address":"10.0.0.1","Port":80,"Meta":{}}
    }""")
    assertEquals(
      services,
      List(
        AgentService(
          ServiceId("h:web:80"),
          ServiceName("web"),
          List(Tag("a")),
          "10.0.0.1",
          80
        )
      )
    )

  test("null or missing Tags decode as no tags"):
    val services = decodeServices(json"""{
      "a": {"ID":"a","Service":"a","Tags":null,"Address":"","Port":0},
      "b": {"ID":"b","Service":"b","Address":"","Port":0}
    }""")
    assertEquals(services.map(_.tags), List(Nil, Nil))

  test("a check reports its ID, service, and definition"):
    val checks = decodeChecks(json"""{
      "service:h:web:80": {"CheckID":"service:h:web:80","ServiceID":"h:web:80",
        "Definition":{"HTTP":"http://h/x","Interval":"5s","Timeout":"1s"}}
    }""")
    assertEquals(
      checks,
      List(
        AgentCheck(
          "service:h:web:80",
          Some(ServiceId("h:web:80")),
          Some(
            CheckDefinition(
              None,
              Some("http://h/x"),
              None,
              Some("5s"),
              Some("1s")
            )
          )
        )
      )
    )

  test(
    "Consul 1.21's top-level type and timing decode when Definition is empty"
  ):
    val checks = decodeChecks(json"""{
      "service:h:web:80": {"CheckID":"service:h:web:80","ServiceID":"h:web:80",
        "Type":"http","Interval":"1m30s","Timeout":"","Definition":{}},
      "service:h:db:81": {"CheckID":"service:h:db:81","ServiceID":"h:db:81",
        "Type":"tcp","Interval":"7s","Timeout":"2s","Definition":{}}
    }""")
    assertEquals(
      checks.sortBy(_.checkId).map(_.definition),
      List(
        Some(CheckDefinition(Some("tcp"), None, None, Some("7s"), Some("2s"))),
        Some(CheckDefinition(Some("http"), None, None, Some("1m30s"), None))
      )
    )

  test("a reported Definition keeps its target and gains the top-level type"):
    val checks = decodeChecks(json"""{
      "c": {"CheckID":"c","ServiceID":"s","Type":"tcp","Interval":"9s",
        "Definition":{"TCP":"h:80","Interval":"5s"}}
    }""")
    assertEquals(
      checks.flatMap(_.definition),
      List(CheckDefinition(Some("tcp"), None, Some("h:80"), Some("5s"), None))
    )

  test("empty top-level type and timing decode as no definition"):
    val checks = decodeChecks(json"""{
      "c": {"CheckID":"c","ServiceID":"s","Type":"","Interval":"",
        "Timeout":"","Definition":{}},
      "d": {"CheckID":"d","ServiceID":"s","Type":5,"Interval":[],
        "Definition":{}}
    }""")
    assertEquals(checks.map(_.definition), List(None, None))

  test("an empty ServiceID decodes as no service"):
    val checks = decodeChecks(json"""{
      "serfHealth": {"CheckID":"serfHealth","ServiceID":"","Definition":{}}
    }""")
    assertEquals(checks.map(_.serviceId), List(None))

  test("a missing, null, empty, or zero-valued Definition decodes as None"):
    val checks = decodeChecks(json"""{
      "missing": {"CheckID":"missing","ServiceID":"s"},
      "null": {"CheckID":"null","ServiceID":"s","Definition":null},
      "empty": {"CheckID":"empty","ServiceID":"s","Definition":{}},
      "zeroString": {"CheckID":"zeroString","ServiceID":"s",
        "Definition":{"HTTP":"","TCP":"","Interval":"0s","Timeout":"0s"}},
      "zeroNumber": {"CheckID":"zeroNumber","ServiceID":"s",
        "Definition":{"HTTP":"","TCP":"","Interval":0,"Timeout":0}}
    }""")
    assertEquals(checks.size, 5)
    assertEquals(checks.map(_.definition).distinct, List(None))

  test("a definition with numeric nanosecond durations renders Go durations"):
    val checks = decodeChecks(json"""{
      "c": {"CheckID":"c","ServiceID":"s",
        "Definition":{"TCP":"h:80","Interval":5000000000,"Timeout":1500000000}}
    }""")
    assertEquals(
      checks.flatMap(_.definition),
      List(CheckDefinition(None, None, Some("h:80"), Some("5s"), Some("1.5s")))
    )

  test(
    "an undecodable value inside a Definition decodes that definition as None"
  ):
    val checks = decodeChecks(json"""{
      "c": {"CheckID":"c","ServiceID":"s",
        "Definition":{"HTTP":"http://h/x","Interval":1.5}}
    }""")
    assertEquals(checks.map(_.definition), List(None))

  test("the advertised address is the agent member's address"):
    assertEquals(
      json"""{"Config":{"Datacenter":"dc1"},"Member":{"Name":"n","Addr":"10.9.8.7"}}"""
        .as(using ConsulCodecs.memberAddress),
      Right("10.9.8.7")
    )
end ConsulCodecsSuite
