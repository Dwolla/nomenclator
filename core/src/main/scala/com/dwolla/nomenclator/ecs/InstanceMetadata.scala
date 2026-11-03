package com.dwolla.nomenclator.ecs

final case class InstanceMetadata(cluster: String, containerInstanceArn: String):

  /** The AWS region the container instance is in, as its ARN
    * (`arn:<partition>:ecs:<region>:<account>:container-instance/...`) names
    * it.
    */
  def region: Option[String] =
    containerInstanceArn.split(":", 6).toList match
      case "arn" :: _ :: "ecs" :: region :: _ if region.nonEmpty => Some(region)
      case _                                                     => None
end InstanceMetadata
