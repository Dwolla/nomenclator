package com.dwolla.nomenclator.integration

/** A Consul release the agent client is checked against, with what that release
  * does differently. Every field below was observed against the real agent in
  * `ConsulAgentIntegrationSuite`, not taken from documentation.
  *
  * What every version does alike: tags are stored and reported verbatim, an
  * empty tag and a duplicate included (`["a","","a"]` comes back as sent), so
  * the planner compares tags as registrator sends them and its plan converges;
  * `ReconcilerIntegrationSuite` pins this.
  *
  * @param image
  *   the container image of a dev-mode agent
  * @param platform
  *   forces the image's platform when it is not published for every CPU
  * @param aclConfig
  *   agent configuration (JSON, for `CONSUL_LOCAL_CONFIG`) that turns ACLs on
  *   with a default policy of deny and [[ConsulVersion.managementToken]] as the
  *   token that may do anything
  * @param reportsCheckTiming
  *   whether `/v1/agent/checks` reports a service check's type, interval, and
  *   timeout. No version reports the HTTP URL or TCP target, and both return
  *   `"Definition": {}`. 1.0.6 reports nothing more, so its
  *   checks are judged by their presence; 1.21 adds top-level `Type`,
  *   `Interval`, and `Timeout` fields (`""` when unset), which the planner
  *   compares.
  * @param reportsAclFiltering
  *   whether the agent's `/v1/agent/services` and `/v1/agent/checks` answer
  *   with `X-Consul-Results-Filtered-By-ACLs: true` when the token can't read
  *   everything. 1.21 does, so the agent client fails such a read; 1.0.6 drops
  *   what the token can't read without saying so, which no client can detect.
  * @param legacyAcls
  *   whether the agent has the legacy ACL system, whose tokens are created with
  *   `/v1/acl/create` rather than from policies
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
                                reportsCheckTiming: Boolean,
                                reportsAclFiltering: Boolean,
                                legacyAcls: Boolean,
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
    reportsCheckTiming = false,
    reportsAclFiltering = false,
    legacyAcls = true,
    unknownDeregisterStatus = None
  )

  val current: ConsulVersion = ConsulVersion(
    label = "consul 1.21",
    image = "hashicorp/consul:1.21.5",
    platform = None,
    aclConfig =
      s"""{"acl":{"enabled":true,"default_policy":"deny","tokens":{"initial_management":"$managementToken"}}}""",
    reportsCheckTiming = true,
    reportsAclFiltering = true,
    legacyAcls = false,
    unknownDeregisterStatus = Some(404)
  )

  val all: List[ConsulVersion] = List(legacy, current)
end ConsulVersions
