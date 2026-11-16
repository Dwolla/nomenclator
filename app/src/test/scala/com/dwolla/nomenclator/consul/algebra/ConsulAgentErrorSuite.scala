package com.dwolla.nomenclator.consul.algebra

import munit.FunSuite

class ConsulAgentErrorSuite extends FunSuite:
  test("UnexpectedStatus names the request, the status, and a body excerpt"):
    assertEquals(
      ConsulAgentError
        .UnexpectedStatus("PUT", "/v1/agent/service/register", 500, "boom")
        .getMessage,
      "Consul agent answered PUT /v1/agent/service/register with status 500: boom"
    )

  test("ResultsFiltered says the token must be able to read everything"):
    assertEquals(
      ConsulAgentError.ResultsFiltered("/v1/agent/services").getMessage,
      "Consul agent filtered the results of GET /v1/agent/services by ACL; the token must be able to read every service and check on the agent"
    )
end ConsulAgentErrorSuite
