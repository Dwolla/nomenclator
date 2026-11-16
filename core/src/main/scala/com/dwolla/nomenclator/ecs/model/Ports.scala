package com.dwolla.nomenclator.ecs.model

import com.comcast.ip4s.Port

import java.util.Locale

/** A port inside a container.
  */
opaque type ContainerPort = Port

object ContainerPort:
  def from(value: Int): Either[String, ContainerPort] =
    Port
      .fromInt(value)
      .toRight(s"ContainerPort must be between 0 and 65535, got $value")

  extension (self: ContainerPort) def value: Port = self
end ContainerPort

/** A port published on the container instance's host.
  */
opaque type HostPort = Port

object HostPort:
  def from(value: Int): Either[String, HostPort] =
    Port
      .fromInt(value)
      .toRight(s"HostPort must be between 0 and 65535, got $value")

  extension (self: HostPort) def value: Port = self
end HostPort

enum Protocol:
  case Tcp, Udp

  def value: String = this match
    case Tcp => "tcp"
    case Udp => "udp"
end Protocol

object Protocol:
  def fromString(value: String): Either[String, Protocol] =
    value.toLowerCase(Locale.ROOT) match
      case "tcp" => Right(Tcp)
      case "udp" => Right(Udp)
      case _     => Left(s"Protocol must be tcp or udp, got '$value'")

final case class PortBinding(containerPort: ContainerPort, hostPort: HostPort, protocol: Protocol)
