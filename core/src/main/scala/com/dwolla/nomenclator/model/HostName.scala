package com.dwolla.nomenclator.model

/** The name of the container instance's host: non-empty, and without a port
  * separator.
  */
opaque type HostName = String

object HostName:
  def from(value: String): Either[String, HostName] =
    if (value.isEmpty) Left("HostName must not be empty")
    else if (value.contains(':'))
      Left(s"HostName must not contain ':', got '$value'")
    else Right(value)

  extension (self: HostName) def value: String = self
end HostName
