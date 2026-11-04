package com.dwolla.nomenclator.telemetry

import io.opentelemetry.sdk.metrics.data.*

import scala.jdk.CollectionConverters.*

/** Reads metrics collected by the OpenTelemetry testkit as plain values.
  */
object CollectedMetrics:

  /** Each point of the metric named `name`, keyed by its attributes. A sum's or
    * gauge's point is its value; a histogram's is its count.
    */
  def points(metrics: List[MetricData], name: String): Map[Map[String, String], Double] =
    metrics
      .filter(_.getName == name)
      .flatMap(_.getData.getPoints.asScala)
      .map(point => attributes(point) -> value(point))
      .toMap

  /** The unit of the metric named `name`, if it was collected.
    */
  def unit(metrics: List[MetricData], name: String): Option[String] =
    metrics.find(_.getName == name).map(_.getUnit)

  private def attributes(point: PointData): Map[String, String] =
    point.getAttributes.asMap.asScala.toMap.map((k, v) => k.getKey -> v.toString)

  private def value(point: PointData): Double = point match
    case p: LongPointData      => p.getValue.toDouble
    case p: DoublePointData    => p.getValue
    case p: HistogramPointData => p.getCount.toDouble
    case other                 => throw new IllegalArgumentException(s"unexpected $other")
end CollectedMetrics
