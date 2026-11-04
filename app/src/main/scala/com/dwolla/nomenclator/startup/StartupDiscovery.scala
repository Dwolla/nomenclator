package com.dwolla.nomenclator.startup

import cats.MonadThrow
import cats.effect.*
import cats.syntax.all.*
import com.dwolla.nomenclator.consul.agent.ConsulAgent
import com.dwolla.nomenclator.ecs.agent.EcsAgentIntrospection
import com.dwolla.nomenclator.model.{HostName, ServiceAddress}
import org.typelevel.log4cats.StructuredLogger
import smithy4s.aws.AwsRegion

/** Fills in, at startup, the settings the configuration leaves unset: from the
  * local host, the Consul agent, and the ECS agent.
  */
object StartupDiscovery:

  /** The configured name, otherwise the local host's, which must be usable in
    * service IDs.
    */
  def hostName[F[_]: MonadThrow](configured: Option[HostName], local: F[String]): F[HostName] =
    configured.fold(
      local
        .adaptError:
          case e =>
            StartupError(
              s"Could not find the local host's name (${e.getClass.getSimpleName}: ${e.getMessage}); set NOMENCLATOR_HOSTNAME"
            )
        .flatMap(name =>
          HostName
            .from(name)
            .leftMap(reason =>
              StartupError(
                s"The local host's name '$name' can't be used in service IDs ($reason); set NOMENCLATOR_HOSTNAME"
              )
            )
            .liftTo[F]
        )
    )(_.pure[F])

  /** The configured address, otherwise the one the Consul agent advertises.
    */
  def address[F[_]: Temporal: StructuredLogger]
    (configured: Option[ServiceAddress], consul: ConsulAgent[F], retry: StartupRetry): F[ServiceAddress] =
    configured.fold(
      retry("the Consul agent's advertised address")(consul.advertisedAddress)
    )(_.pure[F])

  /** The configured region, otherwise the one the container instance's ARN
    * names.
    */
  def region[F[_]: Temporal: StructuredLogger]
    (configured: Option[AwsRegion], intro: EcsAgentIntrospection[F], retry: StartupRetry): F[AwsRegion] =
    configured.fold(
      retry("the ECS agent's metadata")(intro.metadata)
        .flatMap(metadata =>
          metadata.region
            .map(AwsRegion(_))
            .toRight(
              StartupError(
                s"AWS_REGION is unset, and the container instance ARN '${metadata.containerInstanceArn}' names no region; set AWS_REGION"
              )
            )
            .liftTo[F]
        )
    )(_.pure[F])
end StartupDiscovery
