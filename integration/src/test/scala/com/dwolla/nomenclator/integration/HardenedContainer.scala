package com.dwolla.nomenclator.integration

import com.dimafeng.testcontainers.GenericContainer
import com.github.dockerjava.api.model.Capability
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.startupcheck.StartupCheckStrategy
import org.testcontainers.containers.startupcheck.StartupCheckStrategy.StartupStatus

/** Runs the Nomenclator image the way production does: host network, read-only
  * root filesystem, user 65532, every capability dropped, and a memory limit of
  * 80 MiB with no swap on top of it. The command is the deployment environment,
  * `Local`.
  *
  * The container counts as started as soon as Docker has started it, even if it
  * has already exited: some tests expect it to exit.
  */
object HardenedContainer:
  private val memoryLimitBytes = 80L * 1024 * 1024

  private val startedWhetherOrNotStillRunning = new StartupCheckStrategy:
    override def checkStartupState
      (dockerClient: com.github.dockerjava.api.DockerClient, containerId: String): StartupStatus =
      StartupStatus.SUCCESSFUL

  /** Docker Desktop's host networking forwards the host's loopback to a
    * container only for IPv4 sockets: an `AF_INET6` socket connecting to
    * `::ffff:127.0.0.1` or `::1` is refused (checked with a Python probe in a
    * `--network host` container), while an `AF_INET` socket connects. The JDK
    * opens `AF_INET6` dual-stack sockets by default, so on Docker Desktop the
    * tests' loopback fakes, Consul, and collector would all be unreachable.
    * `java.net.preferIPv4Stack` makes the executable use `AF_INET` sockets. It
    * is passed as a run-time argument here, only on Docker Desktop, so that
    * neither the image nor production's Linux host networking changes.
    */
  private def dockerDesktopWorkaround: Seq[String] =
    if (DockerClientFactory.instance.getInfo.getOperatingSystem == "Docker Desktop")
      Seq("-Djava.net.preferIPv4Stack=true")
    else Seq.empty

  def apply(image: String, environment: Map[String, String]): GenericContainer =
    val container = GenericContainer(
      dockerImage = image,
      env = environment,
      command = dockerDesktopWorkaround :+ "Local"
    )
    container.configure: c =>
      c.withNetworkMode("host")
      c.withStartupCheckStrategy(startedWhetherOrNotStillRunning)
      c.withCreateContainerCmdModifier: cmd =>
        cmd.withUser("65532")
        cmd.withHostConfig(
          cmd.getHostConfig
            .withReadonlyRootfs(true)
            .withCapDrop(Capability.ALL)
            .withMemory(memoryLimitBytes)
            .withMemorySwap(memoryLimitBytes)
        )
        ()
      ()
    container
  end apply
end HardenedContainer
