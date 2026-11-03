package com.dwolla.nomenclator.ecs

import cats.syntax.all.*

final case class InstanceMetadata(cluster: String, containerInstanceArn: String):

  /** The AWS region the container instance is in, as its ARN
    * (`arn:<partition>:ecs:<region>:<account>:container-instance/...`) names
    * it.
    */
  def region: Option[String] =
    containerInstanceArn.split(":", 6).toList match
      case "arn" :: _ :: "ecs" :: region :: _ if region.nonEmpty => region.some
      case _                                                     => None
end InstanceMetadata
