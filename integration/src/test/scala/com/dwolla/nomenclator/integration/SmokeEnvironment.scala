package com.dwolla.nomenclator.integration

import cats.effect.*
import cats.syntax.all.*

/** Serves a fake ECS agent and fake AWS endpoints until stopped, so the real
  * application can be run on a workstation against a local Consul agent. Prints
  * the environment variables that point Nomenclator at them. See the README's
  * smoke test.
  */
object SmokeEnvironment extends IOApp.Simple:
  override val run: IO[Unit] =
    (FakeEcsAgent.resource, FakeAws.resource).tupled.use: (ecsAgent, aws) =>
      IO.println(
        s"""export NOMENCLATOR_ECS_AGENT_URL=${ecsAgent.base.renderString}
           |export AWS_CONTAINER_CREDENTIALS_FULL_URI=${aws.credentials.renderString}
           |export AWS_ENDPOINT_URL_ECS=${aws.base.renderString}""".stripMargin
      ) >> IO.never
