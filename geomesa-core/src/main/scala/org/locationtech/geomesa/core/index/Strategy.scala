/*
 * Copyright 2014-2014 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.core.index

import java.util.Map.Entry

import com.vividsolutions.jts.geom.{Geometry, Polygon}
import org.apache.accumulo.core.client.{BatchScanner, IteratorSetting}
import org.apache.accumulo.core.data.{Key, Value}
import org.geotools.data.Query
import org.geotools.filter.text.ecql.ECQL
import org.joda.time.Interval
import org.locationtech.geomesa.core._
import org.locationtech.geomesa.core.data._
import org.locationtech.geomesa.core.index.QueryHints._
import org.locationtech.geomesa.core.index.QueryPlanner._
import org.locationtech.geomesa.core.iterators.{FEATURE_ENCODING, _}
import org.locationtech.geomesa.core.util.SelfClosingIterator
import org.locationtech.geomesa.feature.FeatureEncoding.FeatureEncoding
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.Filter
import org.opengis.filter.expression.{Expression, Literal, PropertyName}

import scala.collection.JavaConversions._
import scala.util.Random

trait Strategy {
  def execute(acc: AccumuloConnectorCreator,
              iqp: QueryPlanner,
              featureType: SimpleFeatureType,
              query: Query,
              output: ExplainerOutputType): SelfClosingIterator[Entry[Key, Value]]

  def configureBatchScanner(bs: BatchScanner, qp: QueryPlan) {
    qp.iterators.foreach { i => bs.addScanIterator(i) }
    bs.setRanges(qp.ranges)
    qp.cf.foreach { c => bs.fetchColumnFamily(c) }
  }

  def configureFeatureEncoding(cfg: IteratorSetting, featureEncoding: FeatureEncoding) {
    cfg.addOption(FEATURE_ENCODING, featureEncoding.toString)
  }

  def configureStFilter(cfg: IteratorSetting, filter: Option[Filter]) = {
    filter.foreach { f => cfg.addOption(ST_FILTER_PROPERTY_NAME, ECQL.toCQL(f)) }
  }

  def configureFeatureType(cfg: IteratorSetting, featureType: SimpleFeatureType) = {
    val encodedSimpleFeatureType = SimpleFeatureTypes.encodeType(featureType)
    cfg.addOption(GEOMESA_ITERATORS_SIMPLE_FEATURE_TYPE, encodedSimpleFeatureType)
    cfg.encodeUserData(featureType.getUserData, GEOMESA_ITERATORS_SIMPLE_FEATURE_TYPE)
  }

  def configureFeatureTypeName(cfg: IteratorSetting, featureType: String) =
    cfg.addOption(GEOMESA_ITERATORS_SFT_NAME, featureType)

  def configureIndexValues(cfg: IteratorSetting, featureType: SimpleFeatureType) = {
    val encodedSimpleFeatureType = SimpleFeatureTypes.encodeType(featureType)
    cfg.addOption(GEOMESA_ITERATORS_SFT_INDEX_VALUE, encodedSimpleFeatureType)
  }

  def configureAttributeName(cfg: IteratorSetting, attributeName: String) =
    cfg.addOption(GEOMESA_ITERATORS_ATTRIBUTE_NAME, attributeName)

  def configureEcqlFilter(cfg: IteratorSetting, ecql: Option[String]) =
    ecql.foreach(filter => cfg.addOption(GEOMESA_ITERATORS_ECQL_FILTER, filter))

  // returns the SimpleFeatureType for the query's transform
  def transformedSimpleFeatureType(query: Query): Option[SimpleFeatureType] = {
    Option(query.getHints.get(TRANSFORM_SCHEMA)).map {_.asInstanceOf[SimpleFeatureType]}
  }

  // store transform information into an Iterator's settings
  def configureTransforms(cfg: IteratorSetting, query:Query) =
    for {
      transformOpt  <- Option(query.getHints.get(TRANSFORMS))
      transform     = transformOpt.asInstanceOf[String]
      _             = cfg.addOption(GEOMESA_ITERATORS_TRANSFORM, transform)
      sfType        <- transformedSimpleFeatureType(query)
      encodedSFType = SimpleFeatureTypes.encodeType(sfType)
      _             = cfg.addOption(GEOMESA_ITERATORS_TRANSFORM_SCHEMA, encodedSFType)
    } yield Unit

  def configureRecordTableIterator(
      simpleFeatureType: SimpleFeatureType,
      featureEncoding: FeatureEncoding,
      ecql: Option[Filter],
      query: Query): IteratorSetting = {

    val cfg = new IteratorSetting(
      iteratorPriority_SimpleFeatureFilteringIterator,
      classOf[RecordTableIterator].getSimpleName,
      classOf[RecordTableIterator]
    )
    configureFeatureType(cfg, simpleFeatureType)
    configureFeatureEncoding(cfg, featureEncoding)
    configureEcqlFilter(cfg, ecql.map(ECQL.toCQL))
    configureTransforms(cfg, query)
    cfg
  }

  def randomPrintableString(length:Int=5) : String = (1 to length).
    map(i => Random.nextPrintableChar()).mkString

  def getDensityIterCfg(query: Query,
                    geometryToCover: Geometry,
                    schema: String,
                    featureEncoding: FeatureEncoding,
                    featureType: SimpleFeatureType) = {
    if (query.getHints.containsKey(DENSITY_KEY)) {
      val clazz = classOf[DensityIterator]

      val cfg = new IteratorSetting(iteratorPriority_AnalysisIterator,
        "topfilter-" + randomPrintableString(5),
        clazz)

      val width = query.getHints.get(WIDTH_KEY).asInstanceOf[Int]
      val height = query.getHints.get(HEIGHT_KEY).asInstanceOf[Int]
      val polygon = if (geometryToCover == null) null else geometryToCover.getEnvelope.asInstanceOf[Polygon]

      DensityIterator.configure(cfg, polygon, width, height)

      cfg.addOption(DEFAULT_SCHEMA_NAME, schema)
      configureFeatureEncoding(cfg, featureEncoding)
      configureFeatureType(cfg, featureType)

      Some(cfg)
    } else if (query.getHints.containsKey(TEMPORAL_DENSITY_KEY)) {
      val clazz = classOf[TemporalDensityIterator]

      val cfg = new IteratorSetting(iteratorPriority_AnalysisIterator,
        "topfilter-" + randomPrintableString(5),
        clazz)

      val interval = query.getHints.get(TIME_INTERVAL_KEY).asInstanceOf[Interval]
      val buckets = query.getHints.get(TIME_BUCKETS_KEY).asInstanceOf[Int]

      TemporalDensityIterator.configure(cfg, interval, buckets)

      configureFeatureEncoding(cfg, featureEncoding)
      configureFeatureType(cfg, featureType)

      Some(cfg)
    } else {
      None
    }
  }
}

trait StrategyProvider {

  /**
   * Returns details on a potential strategy if the filter is valid for this strategy.
   *
   * @param filter
   * @param sft
   * @return
   */
  def getStrategy(filter: Filter, sft: SimpleFeatureType, hints: StrategyHints): Option[StrategyDecision]

  /**
   * Checks the order of properties and literals in the expression
   *
   * @param one
   * @param two
   * @return (prop, literal, whether the order was flipped)
   */
  def checkOrder[T](one: Expression, two: Expression): Option[PropertyLiteral] =
  // TODO move this
    (one, two) match {
      case (p: PropertyName, l: Literal) => Some(PropertyLiteral(p.getPropertyName, l, None, false))
      case (l: Literal, p: PropertyName) => Some(PropertyLiteral(p.getPropertyName, l, None, true))
      case (_: PropertyName, _: PropertyName) | (_: Literal, _: Literal) => None
      case _ =>
        val msg = s"Unhandled expressions in strategy: ${one.getClass.getName}, ${two.getClass.getName}"
        throw new RuntimeException(msg)
    }
}

case class StrategyDecision(strategy: Strategy, cost: Long)

case class PropertyLiteral(name: String, literal: Literal, secondary: Option[Literal], flipped: Boolean = false)
