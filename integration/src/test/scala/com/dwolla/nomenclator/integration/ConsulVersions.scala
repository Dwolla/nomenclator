package com.dwolla.nomenclator.integration

/** A Consul release the agent client is checked against, with what that release
  * does differently. Every field below was observed against the real agent in
  * `ConsulAgentIntegrationSuite`, not taken from documentation.
  *
  * @param image
  *   the container image of a dev-mode agent
  * @param platform
  *   forces the image's platform when it is not published for every CPU
  * @param aclConfig
  *   agent configuration (JSON, for `CONSUL_LOCAL_CONFIG`) that turns ACLs on
  *   with a default policy of deny and [[ConsulVersion.managementToken]] as the
  *   token that may do anything
  * @param reportsCheckDefinition
  *   whether `/v1/agent/checks` reports a service check's HTTP or TCP target
  *   and interval in `Definition`. Neither version does: 1.0.6
  *   returns `"Definition": {}` and 1.21 also returns `"Definition": {}`, while
  *   adding top-level `Type`, `Interval`, and `Timeout` fields that the agent
  *   client does not read. The planner therefore compares check definitions
  *   with no agent that reports one.
  * @param unknownDeregisterStatus
  *   the status the agent itself answers to deregistering a service ID it
  *   doesn't have, or none when it accepts it. The client treats 404 as
  *   success, so this is documentation of the agents, not a test expectation.
  */
final case class ConsulVersion(
                                label: String,
                                image: String,
                                platform: Option[String],
                                aclConfig: String,
                                reportsCheckDefinition: Boolean,
                                unknownDeregisterStatus: Option[Int]
)

object ConsulVersion:
  val managementToken = "nomenclator-test-management-token"

object ConsulVersions:
  import ConsulVersion.managementToken

  /** The oldest agent the client is checked against. It is published for amd64
    * only, so on an arm64 machine it runs under emulation.
    */
  val legacy: ConsulVersion = ConsulVersion(
    label = "consul 1.0.6",
    image = "consul:1.0.6",
    platform = Some("linux/amd64"),
    aclConfig = s"""{"acl_datacenter":"dc1","acl_master_token":"$managementToken","acl_default_policy":"deny"}""",
    reportsCheckDefinition = false,
    unknownDeregisterStatus = None
  )

  val current: ConsulVersion = ConsulVersion(
    label = "consul 1.21",
    image = "hashicorp/consul:1.21.5",
    platform = None,
    aclConfig =
      s"""{"acl":{"enabled":true,"default_policy":"deny","tokens":{"initial_management":"$managementToken"}}}""",
    reportsCheckDefinition = false,
    unknownDeregisterStatus = Some(404)
  )

  val all: List[ConsulVersion] = List(legacy, current)
end ConsulVersions
