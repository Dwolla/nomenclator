package com.dwolla.nomenclator

import cats.effect.*
import org.slf4j.bridge.SLF4JBridgeHandler

/** Keeps a Consul agent's services in step with the ECS tasks on its host. The
  * first argument names the deployment environment.
  */
object Main extends IOApp:
  override def run(args: List[String]): IO[ExitCode] =
    // The OpenTelemetry SDK logs its own problems through JUL; this sends
    // them to the same JSON log as everything else.
    IO:
      SLF4JBridgeHandler.removeHandlersForRootLogger()
      SLF4JBridgeHandler.install()
    >> NomenclatorApp.run[IO](args, runtime.metrics)
