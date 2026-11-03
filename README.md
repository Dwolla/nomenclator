# Nomenclator

Nomenclator keeps a Consul agent's service catalog in step with the containers running on an Amazon ECS host. It reads the ECS agent's introspection endpoints and the task definitions' registration settings, then registers, updates, and deregisters Consul services so the catalog matches what is actually running.

Run `scripts/check` to format-check, compile under fatal warnings, and run every test. It runs `sbt check` and writes the full output to `target/check.log`, printing that path if the check fails.
