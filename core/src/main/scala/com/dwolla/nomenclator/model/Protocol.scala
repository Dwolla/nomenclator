package com.dwolla.nomenclator.model

import java.util.Locale

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
