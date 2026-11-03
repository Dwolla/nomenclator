package com.dwolla.nomenclator.telemetry

import cats.Monad
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.trace.Tracer

/** Adds the current span's `trace_id` and `span_id` to the context of every
  * line, so a log line can be found from its trace and the other way round.
  */
object TraceContextLogger:
  def apply[F[_]: Monad: Tracer](logger: StructuredLogger[F]): StructuredLogger[F] = new StructuredLogger[F]:
    private val traceContext: F[Map[String, String]] =
      Tracer[F].currentSpanContext.map(
        _.filter(_.isValid).fold(Map.empty[String, String])(span =>
          Map("trace_id" -> span.traceIdHex, "span_id" -> span.spanIdHex)
        )
      )

    private def withTrace(ctx: Map[String, String], log: Map[String, String] => F[Unit]): F[Unit] =
      traceContext.flatMap(trace => log(ctx ++ trace))

    override def trace(ctx: Map[String, String])(msg: => String): F[Unit] =
      withTrace(ctx, logger.trace(_)(msg))
    override def trace(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
      withTrace(ctx, logger.trace(_, t)(msg))
    override def debug(ctx: Map[String, String])(msg: => String): F[Unit] =
      withTrace(ctx, logger.debug(_)(msg))
    override def debug(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
      withTrace(ctx, logger.debug(_, t)(msg))
    override def info(ctx: Map[String, String])(msg: => String): F[Unit] =
      withTrace(ctx, logger.info(_)(msg))
    override def info(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
      withTrace(ctx, logger.info(_, t)(msg))
    override def warn(ctx: Map[String, String])(msg: => String): F[Unit] =
      withTrace(ctx, logger.warn(_)(msg))
    override def warn(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
      withTrace(ctx, logger.warn(_, t)(msg))
    override def error(ctx: Map[String, String])(msg: => String): F[Unit] =
      withTrace(ctx, logger.error(_)(msg))
    override def error(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
      withTrace(ctx, logger.error(_, t)(msg))

    override def trace(t: Throwable)(message: => String): F[Unit] =
      trace(Map.empty, t)(message)
    override def trace(message: => String): F[Unit] = trace(Map.empty)(message)
    override def debug(t: Throwable)(message: => String): F[Unit] =
      debug(Map.empty, t)(message)
    override def debug(message: => String): F[Unit] = debug(Map.empty)(message)
    override def info(t: Throwable)(message: => String): F[Unit] =
      info(Map.empty, t)(message)
    override def info(message: => String): F[Unit] = info(Map.empty)(message)
    override def warn(t: Throwable)(message: => String): F[Unit] =
      warn(Map.empty, t)(message)
    override def warn(message: => String): F[Unit] = warn(Map.empty)(message)
    override def error(t: Throwable)(message: => String): F[Unit] =
      error(Map.empty, t)(message)
    override def error(message: => String): F[Unit] = error(Map.empty)(message)
end TraceContextLogger
