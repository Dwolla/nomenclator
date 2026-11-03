package com.dwolla.nomenclator.model

import com.comcast.ip4s.Port

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
