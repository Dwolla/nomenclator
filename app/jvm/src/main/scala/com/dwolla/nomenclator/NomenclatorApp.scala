package com.dwolla.nomenclator

import cats.Applicative
import cats.effect.*
import cats.effect.metrics.CpuStarvationWarningMetrics
import cats.effect.std.Env
import cats.effect.syntax.all.*
import cats.effect.unsafe.metrics.IORuntimeMetrics as RuntimeMetrics
import cats.syntax.all.*
import com.dwolla.nomenclator.aws.ContainerCredentials
import com.dwolla.nomenclator.config.NomenclatorConfig
import com.dwolla.nomenclator.consul.agent.{ConsulAgent, Http4sConsulAgent}
import com.dwolla.nomenclator.ecs.agent.{EcsAgentIntrospection, Http4sEcsAgentIntrospection}
import com.dwolla.nomenclator.ecs.api.*
import com.dwolla.nomenclator.model.HostName
import com.dwolla.nomenclator.reconcile.*
import com.dwolla.nomenclator.registration.HostIdentity
import com.dwolla.nomenclator.settings.GoDuration
import com.dwolla.nomenclator.startup.*
import com.dwolla.nomenclator.telemetry.*
import com.dwolla.nomenclator.telemetry.AlgebraAspects.given
import com.dwolla.tracing.DwollaEnvironment
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.{LoggerFactory, StructuredLogger}
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import smithy4s.aws.AwsRegion

import java.net.InetAddress
import scala.concurrent.duration.*

/** Nomenclator, wired together from its configuration.
  */
object NomenclatorApp:
  private val name = "com.dwolla.nomenclator"

  /** Bounds waiting for a response's headers. A stalled connect isn't covered,
    * which is why each pass has its own timeout.
    */
  private val requestTimeout = 10.seconds

  /** Bounds a stalled read or write on an open connection. Longer than
    * `requestTimeout`, as Ember recommends.
    */
  private val idleConnectionTime = 15.seconds

  /** Runs Nomenclator in the environment the first of `args` names, until it is
    * stopped or gives up. Why it exits is logged.
    */
  def run[F[_]: Async: LiftIO: Env](args: List[String], runtimeMetrics: RuntimeMetrics): F[ExitCode] =
    given LoggerFactory[F] = Slf4jFactory.create[F]
    given StructuredLogger[F] = logger[F]
    (for
      environment <- environment[F](args)
      config <- NomenclatorConfig.load[F]
      _ <- logStartup[F](config, environment)
      exit <- resource[F](environment, config, runtimeMetrics).use(identity)
    yield exit).handleErrorWith(e =>
      StructuredLogger[F]
        .error(e)(s"Nomenclator is exiting: ${e.getMessage}")
        .as(ExitCode.Error)
    )

  /** The JSON log every line goes to.
    */
  def logger[F[_]: Sync]: StructuredLogger[F] =
    Slf4jFactory.create[F].getLoggerFromName(name)

  /** Cats Effect's warning that its compute pool is starved of CPU, as a line
    * in the JSON log rather than text on standard error.
    */
  def logCpuStarvation[F[_]: StructuredLogger](metrics: CpuStarvationWarningMetrics): F[Unit] =
    StructuredLogger[F].warn(
      Map(
        "clock_drift" -> GoDuration.render(metrics.clockDrift),
        "starvation_threshold" -> metrics.starvationThreshold.toString,
        "starvation_interval" -> GoDuration.render(metrics.starvationInterval)
      )
    )(
      "Starved of CPU: timers fire late, so passes and timeouts run behind; give the task more CPU or find what is blocking the compute pool"
    )

  /** The environment the first argument names, or `Local`.
    */
  def environment[F[_]: Applicative: StructuredLogger](args: List[String]): F[DwollaEnvironment] =
    args.headOption
      .flatMap(DwollaEnvironment(_))
      .fold(
        StructuredLogger[F]
          .warn(Map("argument" -> args.headOption.getOrElse("<none>")))(
            "The first argument names no known environment; using Local"
          )
          .as(DwollaEnvironment.Local)
      )(_.pure[F])

  /** Starts telemetry and connects to the agents and AWS, then gives the
    * reconcile loop, ready to run. Fails if something it needs can't be found
    * or reached; see [[com.dwolla.nomenclator.startup.StartupDiscovery]].
    */
  def resource[F[_]: Async: LiftIO: Env: LoggerFactory](
                                                         environment: DwollaEnvironment,
                                                         config: NomenclatorConfig,
                                                         runtimeMetrics: RuntimeMetrics
  ): Resource[F, F[ExitCode]] =
    for
      client <- EmberClientBuilder
        .default[F]
        .withTimeout(requestTimeout)
        .withIdleConnectionTime(idleConnectionTime)
        .build
      hostName <- StartupDiscovery
        .hostName[F](
          config.hostName,
          Sync[F].blocking(InetAddress.getLocalHost.getHostName)
        )
        .toResource
      _ <- HostResourceAttribute.install[F](hostName).toResource
      (tracerProvider, meterProvider) <- Telemetry.resource[F](
        environment,
        BuildInfo.version,
        client,
        runtimeMetrics
      )
      tracer <- tracerProvider.get(name).toResource
      meter <- meterProvider.get(name).toResource
      reconcileLoop <-
        given TracerProvider[F] = tracerProvider
        given MeterProvider[F] = meterProvider
        given Tracer[F] = tracer
        given Meter[F] = meter
        given StructuredLogger[F] =
          TraceContextLogger[F](LoggerFactory[F].getLoggerFromName(name))
        connected[F](config, client, hostName)
    yield reconcileLoop

  private def connected[F[_]: Async: TracerProvider: MeterProvider: Tracer: Meter: StructuredLogger]
    (config: NomenclatorConfig, client: Client[F], hostName: HostName): Resource[F, F[ExitCode]] =
    val retry = StartupRetry(
      config.interval,
      config.passTimeout,
      config.failureLimit
    )
    for
      consul <- Instrumentation
        .instrumentAlgebra[ConsulAgent, F](
          Http4sConsulAgent[F](
            client,
            config.consulAddress,
            config.consulToken.map(_.value)
          )
        )
        .toResource
      intro <- Instrumentation
        .instrumentAlgebra[EcsAgentIntrospection, F](
          Http4sEcsAgentIntrospection[F](client, config.ecsAgent)
        )
        .toResource
      address <- StartupDiscovery
        .address[F](config.address, consul, retry)
        .toResource
      region <- StartupDiscovery
        .region[F](config.awsRegion, intro, retry)
        .toResource
      host = HostIdentity(hostName, address)
      _ <- logDiscovered[F](host, region).toResource
      credentialsUri <- credentialsEndpoint(config).liftTo[Resource[F, *]]
      credentials <- retry.acquire("task-role credentials")(
        ContainerCredentials.resource[F](
          client,
          credentialsUri,
          StructuredLogger[F]
        )
      )
      ecs <- EcsClient
        .resource[F](client, region, credentials, config.ecsEndpoint)
        .evalMap(Instrumentation.instrumentEcsClient[F])
      metrics <- NomenclatorMetrics[F](hostName)
      defs <- CachedTaskDefinitions[F](
        ecs,
        metrics.taskDefinitionLookup
      ).toResource
    yield reconcile[F](config, host, intro, defs, consul, metrics)
    end for
  end connected

  /** ECS's task-role endpoint, or the endpoint the standard full-URI variable
    * names.
    */
  def credentialsEndpoint(config: NomenclatorConfig): Either[StartupError, Uri] =
    config.credentialsRelativeUri
      .map(ContainerCredentials.taskRoleEndpoint)
      .orElse(config.credentialsFullUri)
      .toRight(
        StartupError(
          "Neither AWS_CONTAINER_CREDENTIALS_RELATIVE_URI nor AWS_CONTAINER_CREDENTIALS_FULL_URI is set, so there are no credentials for the ECS API; give the task a task role"
        )
      )

  /** Logs the effective configuration, the Consul token redacted.
    */
  def logStartup[F[_]: StructuredLogger](config: NomenclatorConfig, environment: DwollaEnvironment): F[Unit] =
    StructuredLogger[F].info(
      config.logContext ++ Map(
        "environment" -> environment.name,
        "version" -> BuildInfo.version
      )
    )("Nomenclator is starting")

  /** Logs what startup discovered about this host.
    */
  def logDiscovered[F[_]: StructuredLogger](host: HostIdentity, region: AwsRegion): F[Unit] =
    StructuredLogger[F].info(
      Map(
        "host_name" -> host.name.value,
        "address" -> host.address.value,
        "aws_region" -> region.value
      )
    )("Discovered this host's identity and region")

  /** Runs the reconcile loop, recording each pass, until it is cancelled or
    * gives up. Cancelling it, as `SIGTERM` does, writes nothing to Consul:
    * every registration stays for the next Nomenclator to reconcile.
    */
  def reconcile[F[_]: Temporal: StructuredLogger: Tracer](
                                                           config: NomenclatorConfig,
                                                           host: HostIdentity,
                                                           intro: EcsAgentIntrospection[F],
                                                           defs: TaskDefinitions[F],
                                                           consul: ConsulAgent[F],
                                                           metrics: NomenclatorMetrics[F]
  ): F[ExitCode] =
    Reconciler
      .stream[F](
        config.mode,
        config.interval,
        config.passTimeout,
        config.lookupTimeout,
        host,
        config.globalTags,
        intro,
        defs,
        consul,
        FailurePolicy.exitAfter(config.failureLimit)
      )
      .evalMap(metrics.record(config.mode, _))
      .compile
      .drain
      .as(ExitCode.Success)
      .recoverWith:
        case e: ReconcileFailedTooLong =>
          StructuredLogger[F].error(e.getMessage).as(ExitCode.Error)
      .onCancel(
        StructuredLogger[F].info(
          "Stopping as asked; registrations stay in Consul for the next Nomenclator to reconcile"
        )
      )
end NomenclatorApp
