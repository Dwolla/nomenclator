package com.dwolla.nomenclator.registration

import cats.data.NonEmptyChain
import com.dwolla.nomenclator.consul.ServiceIds
import com.dwolla.nomenclator.ecs.*
import com.dwolla.nomenclator.gen.{EcsGen, ModelGen}
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.settings.{ContainerSettings, SettingsError}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.concurrent.duration.*

class RegistrationsSuite extends ScalaCheckSuite:
  private def ok[A](either: Either[String, A]): A =
    either.fold(fail(_), identity)

  private val host =
    HostIdentity(ok(HostName.from("ip-10-0-0-1")), ServiceAddress("10.0.0.1"))
  private val definition = TaskDefinitionRef(TaskFamily("foo-service"), 3)
  private val containerName = ContainerName("web")
  private val dockerName = DockerName("ecs-foo-service-3-web-a1b2")
  private val image =
    ImageName("registry.example.com:5000/team/foo-service:abc")

  private def binding(containerPort: Int, hostPort: Int, protocol: Protocol = Protocol.Tcp) =
    PortBinding(
      ok(ContainerPort.from(containerPort)),
      ok(HostPort.from(hostPort)),
      protocol
    )

  private def task(
                    ports: List[PortBinding],
                    known: TaskStatus = TaskStatus.Running,
                    desired: TaskStatus = TaskStatus.Running,
                    image: ImageName = image
  ) =
    EcsTask(
      TaskArn("task-arn"),
      definition,
      known,
      desired,
      List(EcsContainer(containerName, dockerName, image, ports))
    )

  private def env(entries: (String, String)*) = Map(
    definition -> Map(
      containerName -> ContainerSettings.fromRaw(entries.toMap, Map.empty)
    )
  )

  private def desired(
                       t: EcsTask,
                       settings: Map[TaskDefinitionRef, Map[ContainerName, ContainerSettings]] = env(),
                       globalTags: List[Tag] = Nil
  ) = Registrations.desired(host, List(t), settings, globalTags)

  private def port(n: Int) = ok(ContainerPort.from(n))
  private def hostPort(n: Int) = ok(HostPort.from(n))

  test("a container with one port and no settings registers with defaults"):
    assertEquals(
      desired(task(List(binding(8080, 32768)))).registrations,
      List(
        Registration(
          ServiceId("ip-10-0-0-1:ecs-foo-service-3-web-a1b2:8080"),
          ServiceName("foo-service"),
          Nil,
          ServiceAddress("10.0.0.1"),
          ok(HostPort.from(32768)),
          None
        )
      )
    )

  test("a generic name is suffixed with the port in a multi-port container"):
    val result = desired(
      task(List(binding(8080, 1), binding(9090, 2))),
      env("SERVICE_NAME" -> "foo")
    )
    assertEquals(
      result.registrations.map(_.name.value),
      List("foo-8080", "foo-9090")
    )

  test("a port-specific name is not suffixed"):
    val result = desired(
      task(List(binding(8080, 1), binding(9090, 2))),
      env("SERVICE_8080_NAME" -> "api")
    )
    assertEquals(
      result.registrations.map(_.name.value),
      List("api", "foo-service-9090")
    )

  test("tcp and udp on one port are two keys: same suffixed name, ids differ"):
    val result = desired(
      task(List(binding(53, 1), binding(53, 2, Protocol.Udp)))
    )
    assertEquals(
      result.registrations.map(r => (r.id.value, r.name.value)),
      List(
        ("ip-10-0-0-1:ecs-foo-service-3-web-a1b2:53", "foo-service-53"),
        ("ip-10-0-0-1:ecs-foo-service-3-web-a1b2:53:udp", "foo-service-53")
      )
    )

  test("an ignored port still counts toward the suffix rule"):
    val result = desired(
      task(List(binding(8080, 1), binding(9090, 2))),
      env("SERVICE_9090_IGNORE" -> "false")
    )
    assertEquals(
      result.registrations.map(_.name.value),
      List("foo-service-8080")
    )
    assertEquals(
      result.skipped,
      List(
        Skipped(
          TaskArn("task-arn"),
          containerName,
          port(9090),
          SkipReason.Ignored
        )
      )
    )

  test("a udp port has a :udp id suffix and a trailing udp tag"):
    val reg = desired(
      task(List(binding(53, 5353, Protocol.Udp))),
      env("SERVICE_TAGS" -> "dns"),
      List(Tag("g"))
    ).registrations.head
    assert(reg.id.value.endsWith(":53:udp"), reg.id.value)
    assertEquals(reg.tags.map(_.value), List("dns", "g", "udp"))

  test("tags are split verbatim: empty elements and spaces are kept"):
    val reg = desired(
      task(List(binding(8080, 1))),
      env("SERVICE_TAGS" -> "a,, b"),
      List(Tag("g"))
    ).registrations.head
    assertEquals(reg.tags.map(_.value), List("a", "", " b", "g"))

  test("an empty SERVICE_TAGS contributes no tags"):
    val reg = desired(
      task(List(binding(8080, 1))),
      env("SERVICE_TAGS" -> ""),
      List(Tag("g"))
    ).registrations.head
    assertEquals(reg.tags.map(_.value), List("g"))

  test("an HTTP check targets the host address and host port"):
    val reg = desired(
      task(List(binding(8080, 32768))),
      env(
        "SERVICE_8080_CHECK_HTTP" -> "/health",
        "SERVICE_8080_CHECK_INTERVAL" -> "5s",
        "SERVICE_8080_CHECK_TIMEOUT" -> "1s"
      )
    ).registrations.head
    assertEquals(
      reg.check,
      Some(
        Check.Http("http://10.0.0.1:32768/health", 5.seconds, Some(1.second))
      )
    )

  test("a TCP check targets the host address and host port"):
    val reg = desired(
      task(List(binding(8080, 32768))),
      env("SERVICE_CHECK_TCP" -> "true")
    ).registrations.head
    assertEquals(reg.check, Some(Check.Tcp("10.0.0.1:32768", 10.seconds, None)))

  test("a script check skips the port"):
    val result = desired(
      task(List(binding(8080, 1))),
      env("SERVICE_CHECK_SCRIPT" -> "x")
    )
    assertEquals(result.registrations, Nil)
    assertEquals(
      result.skipped,
      List(
        Skipped(
          TaskArn("task-arn"),
          containerName,
          port(8080),
          SkipReason.ScriptCheck
        )
      )
    )

  test("a TTL check skips the port"):
    val result = desired(
      task(List(binding(8080, 1))),
      env("SERVICE_CHECK_TTL" -> "30s")
    )
    assertEquals(result.registrations, Nil)
    assertEquals(result.skipped.map(_.reason), List(SkipReason.TtlCheck))

  test("a malformed interval skips only that port"):
    val result = desired(
      task(List(binding(8080, 1), binding(9090, 2))),
      env(
        "SERVICE_8080_CHECK_HTTP" -> "/h",
        "SERVICE_8080_CHECK_INTERVAL" -> "soon"
      )
    )
    assertEquals(
      result.skipped.map(s => (s.port, s.reason)),
      List(
        (
          port(8080),
          SkipReason.InvalidSettings(
            NonEmptyChain.one(
              SettingsError.InvalidDuration("check_interval", "soon")
            )
          )
        )
      )
    )
    assertEquals(
      result.registrations.map(_.port),
      List(hostPort(2))
    )

  test(
    "a task whose known status is Running but desired is Stopped gives nothing"
  ):
    val result = desired(
      task(List(binding(8080, 1)), desired = TaskStatus.Stopped)
    )
    assertEquals(result.registrations, Nil)
    assertEquals(result.awaitingDefinitions, Set.empty[TaskDefinitionRef])

  test("a task that is not yet Running gives nothing"):
    assertEquals(
      desired(
        task(List(binding(8080, 1)), known = TaskStatus.Pending)
      ).registrations,
      Nil
    )

  test(
    "a task with an unknown definition is awaiting it and produces nothing"
  ):
    val result = desired(task(List(binding(8080, 1))), Map.empty)
    assertEquals(result.registrations, Nil)
    assertEquals(result.awaitingDefinitions, Set(definition))

  test(
    "bindings with the same port and protocol give one registration and no suffix"
  ):
    val result = desired(task(List(binding(8080, 1), binding(8080, 2))))
    assertEquals(
      result.registrations.map(r => (r.name.value, r.port.value.value.toString)),
      List(("foo-service", "1"))
    )

  test("SERVICE_ID is not honored and is reported once per container"):
    val t = task(List(binding(8080, 1), binding(9090, 2)))
    val result =
      desired(t, env("SERVICE_ID" -> "custom", "SERVICE_9090_ID" -> "x"))
    assertEquals(
      result.registrations.map(_.id.value),
      List(
        "ip-10-0-0-1:ecs-foo-service-3-web-a1b2:8080",
        "ip-10-0-0-1:ecs-foo-service-3-web-a1b2:9090"
      )
    )
    assertEquals(
      result.customIdRequested,
      List(CustomIdRequest(TaskArn("task-arn"), containerName))
    )

  test("an empty SERVICE_ID is not a custom ID request"):
    assertEquals(
      desired(
        task(List(binding(8080, 1))),
        env("SERVICE_ID" -> "")
      ).customIdRequested,
      Nil
    )

  test("a container missing from the settings map has no settings"):
    val result = desired(
      task(List(binding(8080, 1))),
      Map(definition -> Map.empty[ContainerName, ContainerSettings])
    )
    assertEquals(result.registrations.map(_.name.value), List("foo-service"))

  test(
    "an empty port-specific name suppresses the suffix and falls back to the default"
  ):
    val result = desired(
      task(List(binding(8080, 1), binding(9090, 2))),
      env("SERVICE_8080_NAME" -> "")
    )
    assertEquals(
      result.registrations.map(_.name.value),
      List("foo-service", "foo-service-9090")
    )

  test("an ignored port with a malformed interval is skipped as ignored"):
    val result = desired(
      task(List(binding(8080, 1))),
      env(
        "SERVICE_IGNORE" -> "1",
        "SERVICE_CHECK_HTTP" -> "/h",
        "SERVICE_CHECK_INTERVAL" -> "soon"
      )
    )
    assertEquals(result.skipped.map(_.reason), List(SkipReason.Ignored))

  test("a container with no registrations does not report a custom ID"):
    val result = desired(
      task(List(binding(8080, 1))),
      env("SERVICE_ID" -> "custom", "SERVICE_IGNORE" -> "1")
    )
    assertEquals(result.customIdRequested, Nil)

  property("every registration id starts with the host name"):
    val publishingTask = for
      generated <- EcsGen.ecsTask
      ports <- Gen.nonEmptyListOf(EcsGen.portBinding)
    yield generated.copy(
      knownStatus = TaskStatus.Running,
      desiredStatus = TaskStatus.Running,
      containers = generated.containers.headOption
        .getOrElse(
          EcsContainer(containerName, dockerName, image, Nil)
        )
        .copy(ports = ports) :: Nil
    )
    forAll(ModelGen.hostName, publishingTask): (hostName, running) =>
      val result = Registrations.desired(
        HostIdentity(hostName, ServiceAddress("10.0.0.1")),
        List(running),
        Map(running.definition -> Map.empty),
        Nil
      )
      result.registrations.nonEmpty &&
      result.registrations.forall(
        _.id.value.startsWith(s"${hostName.value}:")
      )

  property("an ignored port never produces a registration"):
    forAll(ModelGen.containerPort, ModelGen.hostPort): (cp, hp) =>
      val t = task(List(PortBinding(cp, hp, Protocol.Tcp)))
      desired(t, env("SERVICE_IGNORE" -> "1")).registrations.isEmpty

  private val runningTasks: Gen[(HostName, List[EcsTask])] = Gen.resize(
    8, {
      val publishingTask = for
        generated <- EcsGen.ecsTask
        containers <- Gen.nonEmptyListOf(EcsGen.ecsContainer)
      yield generated.copy(
        knownStatus = TaskStatus.Running,
        desiredStatus = TaskStatus.Running,
        containers = containers.map(c => c.copy(ports = c.ports.distinctBy(p => (p.containerPort, p.protocol))))
      )
      // Docker gives every container a unique name, so IDs can only collide if
      // the generator repeats one.
      for
        hostName <- ModelGen.hostName
        tasks <- Gen.nonEmptyListOf(publishingTask)
      yield (hostName, uniquelyNamed(tasks))
    }
  )

  private def uniquelyNamed(tasks: List[EcsTask]): List[EcsTask] =
    val containers = tasks.flatMap(_.containers).distinctBy(_.dockerName.value)
    tasks.map(t => t.copy(containers = t.containers.filter(c => containers.exists(_ eq c))))

  private def desiredFor(hostName: HostName, tasks: List[EcsTask]) =
    Registrations.desired(
      HostIdentity(hostName, ServiceAddress("10.0.0.1")),
      tasks,
      tasks
        .map(_.definition -> Map.empty[ContainerName, ContainerSettings])
        .toMap,
      Nil
    )

  property("every desired id is owned by its host"):
    forAll(runningTasks): (hostName, tasks) =>
      desiredFor(hostName, tasks).registrations.forall(r => ServiceIds.owns(hostName)(r.id))

  property("desired ids are distinct"):
    forAll(runningTasks): (hostName, tasks) =>
      val ids = desiredFor(hostName, tasks).registrations.map(_.id.value)
      assertEquals(ids.distinct, ids)
end RegistrationsSuite
