package com.dwolla.nomenclator.model

import com.comcast.ip4s.Port

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
