package com.dwolla.nomenclator.system

import cats.effect.Sync

import java.net.InetAddress

/** The name this machine calls itself.
  */
trait LocalHostName[F[_]]:
  def hostName: F[String]

object LocalHostName:
  def apply[F[_]](using localHostName: LocalHostName[F]): LocalHostName[F] =
    localHostName

  /** Asks the operating system, which can block on a slow resolver. It needs
    * `Sync` for that blocking Java call: neither fs2 nor ip4s has an
    * equivalent, and `Dns.reverse` asks DNS, which is a different question.
    */
  def live[F[_]: Sync]: LocalHostName[F] =
    new LocalHostName[F]:
      val hostName: F[String] =
        Sync[F].blocking(InetAddress.getLocalHost.getHostName)
end LocalHostName
