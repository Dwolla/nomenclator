package com.dwolla.nomenclator.registration

import cats.syntax.all.*
import com.dwolla.nomenclator.consul.model.*
import com.dwolla.nomenclator.ecs.model.*
import com.dwolla.nomenclator.registration.settings.*

/** Computes the registrations that the running tasks ask for, following
  * registrator v7's rules. `SERVICE_ID` is not honored: service IDs always have
  * the host-shaped form.
  */
object Registrations:

  private final case class ContainerResult(
                                            task: TaskArn,
                                            container: ContainerName,
                                            registrations: List[Registration],
                                            skipped: List[Skipped],
                                            customIdRequested: Boolean
  )

  def desired(
               host: HostIdentity,
               tasks: List[EcsTask],
               settings: Map[TaskDefinitionRef, Map[ContainerName, ContainerSettings]],
               globalTags: List[Tag]
  ): Desired =
    val running = tasks.filter(_.isRunning)
    val (awaiting, known) = running.partitionMap: task =>
      settings
        .get(task.definition)
        .toRight(task.definition)
        .map(task -> _)

    val results = known.flatMap: (task, containerSettings) =>
      task.containers.map: container =>
        forContainer(
          host,
          task,
          container,
          containerSettings.getOrElse(
            container.name,
            ContainerSettings(Map.empty, Map.empty)
          ),
          globalTags
        )

    Desired(
      registrations = results.flatMap(_.registrations),
      skipped = results.flatMap(_.skipped),
      awaitingDefinitions = awaiting.toSet,
      customIdRequested = results.collect:
        case result if result.customIdRequested =>
          CustomIdRequest(result.task, result.container)
    )
  end desired

  private def forContainer(
                            host: HostIdentity,
                            task: EcsTask,
                            container: EcsContainer,
                            settings: ContainerSettings,
                            globalTags: List[Tag]
  ): ContainerResult =
    // Registrator publishes one service per port key, from the first binding
    val bindings =
      container.ports.distinctBy(b => (b.containerPort, b.protocol))
    val isGroup = bindings.sizeIs > 1
    val portSettings = bindings.map(binding => binding -> ServiceSettings.forPort(settings, binding.containerPort))
    val (skipped, registrations) = portSettings
      .map: (binding, forBinding) =>
        forPort(host, task, container, binding, forBinding, isGroup, globalTags)
      .separate

    ContainerResult(
      task.arn,
      container.name,
      registrations,
      skipped,
      customIdRequested = registrations.nonEmpty &&
        portSettings.exists((_, forBinding) => forBinding.get("id").isDefined)
    )
  end forContainer

  private def forPort(
                       host: HostIdentity,
                       task: EcsTask,
                       container: EcsContainer,
                       binding: PortBinding,
                       settings: ServiceSettings,
                       isGroup: Boolean,
                       globalTags: List[Tag]
  ): Either[Skipped, Registration] =
    def skipped(reason: SkipReason) =
      Skipped(task.arn, container.name, binding.containerPort, reason)

    def registration(check: Option[Check]) =
      Registration(
        id = ServiceIds.of(
          host.name,
          container.dockerName,
          binding.containerPort,
          binding.protocol
        ),
        name = ServiceNaming.name(
          settings,
          container.image,
          binding.containerPort,
          isGroup
        ),
        tags = ServiceNaming.tags(settings, globalTags, binding.protocol),
        address = host.address,
        port = binding.hostPort,
        check = check
      )

    val target = s"${host.address.value}:${binding.hostPort.value.value}"

    if (settings.ignored) skipped(SkipReason.Ignored).asLeft
    else
      CheckSettings.from(settings) match
        case Left(errors) =>
          skipped(SkipReason.InvalidSettings(errors)).asLeft
        case Right(CheckSettings.Script) =>
          skipped(SkipReason.ScriptCheck).asLeft
        case Right(CheckSettings.Ttl)                           => skipped(SkipReason.TtlCheck).asLeft
        case Right(CheckSettings.NoCheck)                       => registration(None).asRight
        case Right(CheckSettings.Http(path, interval, timeout)) =>
          registration(
            Check.Http(s"http://$target$path", interval, timeout).some
          ).asRight
        case Right(CheckSettings.Tcp(interval, timeout)) =>
          registration(Check.Tcp(target, interval, timeout).some).asRight
  end forPort
end Registrations
