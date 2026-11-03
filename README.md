# Nomenclator

Nomenclator keeps a Consul agent's service catalog in step with the containers running on an Amazon ECS host. It reads the ECS agent's introspection endpoints and the task definitions' registration settings, then registers, updates, and deregisters Consul services so the catalog matches what is actually running.

Run `scripts/check` to format-check, compile under fatal warnings, and run every test. It runs `sbt check` and writes the full output to `target/check.log`, printing that path if the check fails.

## Configuration

Nomenclator reads environment variables. Durations use Go's syntax (`5s`, `1m30s`, `500ms`).

| Variable | Default | Meaning |
|---|---|---|
| `NOMENCLATOR_MODE` | `shadow` | `apply` writes to Consul; `shadow` only logs how Consul differs. |
| `CONSUL_HTTP_ADDR` | `127.0.0.1:8500` | The local Consul agent; plain HTTP unless a scheme is given. |
| `CONSUL_HTTP_TOKEN` | none | ACL token. Never logged. |
| `NOMENCLATOR_INTERVAL` | `5s` | Time between reconcile passes. At most the pass timeout. |
| `NOMENCLATOR_PASS_TIMEOUT` | `30s` | A pass still running after this fails. More than twice the lookup timeout, less than the failure limit. |
| `NOMENCLATOR_LOOKUP_TIMEOUT` | `5s` | Bound on each task definition lookup. |
| `NOMENCLATOR_FAILURE_LIMIT` | `2m` | Exit non-zero after this long without a successful pass. |
| `NOMENCLATOR_TAGS` | none | Tags added to every service, comma-separated, kept exactly as written. |
| `NOMENCLATOR_HOSTNAME` | the host's name | The name that starts every service ID. |
| `NOMENCLATOR_ADDRESS` | the Consul agent's advertised address | The address registered for every service. |
| `NOMENCLATOR_ECS_AGENT_URL` | `http://localhost:51678` | The ECS agent's introspection API. |
| `AWS_REGION` | the region in the container instance's ARN | Region of the ECS API. |
| `AWS_CONTAINER_CREDENTIALS_RELATIVE_URI` | set by ECS | Task-role credentials. Required unless the full URI is set. |
| `AWS_CONTAINER_CREDENTIALS_FULL_URI` | none | A credentials endpoint elsewhere: `https`, or `http` only to a loopback address, `169.254.170.2`, `169.254.170.23`, or `fd00:ec2::23`. No authorization token is sent. |
| `AWS_ENDPOINT_URL_ECS` | the regional endpoint | Sends ECS API calls elsewhere. Scheme, host, and port only; a path is refused. |

The first command-line argument names the deployment environment (`Local`, `DevInt`, `Uat`, `Prod`, `Sandbox`, or
`Admin`); without one, Nomenclator uses `Local` and logs a warning. The standard `OTEL_*` variables configure
telemetry. On `SIGTERM`, Nomenclator stops without deregistering anything.

## Smoke test

This runs the real application on a workstation against a local Consul agent, a fake ECS agent serving the
introspection fixtures, and fake AWS endpoints. Nothing touches AWS; the credentials are made up.

1. Start a dev Consul agent and note its port:

   ```sh
   docker run -d --name nomenclator-smoke-consul -p 0:8500 hashicorp/consul:1.21 agent -dev -client 0.0.0.0
   docker port nomenclator-smoke-consul 8500
   ```

2. In another terminal, start the fakes. They print the variables that point Nomenclator at them, and run until
   stopped with Ctrl-C:

   ```sh
   sbt "integration/Test/runMain com.dwolla.nomenclator.integration.SmokeEnvironment"
   ```

3. In a third terminal, paste the printed `export` lines, then run in shadow mode. Replace `<port>` with Consul's
   port from step 1:

   ```sh
   export CONSUL_HTTP_ADDR=127.0.0.1:<port> NOMENCLATOR_HOSTNAME=smoke-host
   export OTEL_TRACES_EXPORTER=none OTEL_METRICS_EXPORTER=none
   NOMENCLATOR_MODE=shadow sbt "appJVM/run Local"
   ```

   Expected: JSON log lines, starting with `Nomenclator is starting` and the effective configuration, then
   `Discovered this host's identity and region`, then a `Shadow difference appeared` line (`missing`) for each of the fixture's three services. Consul stays empty:
   `curl -s 127.0.0.1:<port>/v1/agent/services` prints `{}`. Stop it with Ctrl-C; it logs `Stopping as asked`.

4. Run in apply mode:

   ```sh
   NOMENCLATOR_MODE=apply sbt "appJVM/run Local"
   ```

   Expected: a `Consul agent change: register` line for each service, and the three `smoke-host:` services in
   `curl -s 127.0.0.1:<port>/v1/agent/services`. After Ctrl-C they are still there.

5. Clean up: stop the fakes with Ctrl-C, then `docker rm -f nomenclator-smoke-consul`.
