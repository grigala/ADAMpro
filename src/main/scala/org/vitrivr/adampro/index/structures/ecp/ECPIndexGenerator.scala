package org.vitrivr.adampro.index.structures.ecp

import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, Dataset}
import org.vitrivr.adampro.config.AttributeNames
import org.vitrivr.adampro.datatypes.TupleID.TupleID
import org.vitrivr.adampro.datatypes.vector.Vector._
import org.vitrivr.adampro.index.Index.IndexTypeName
import org.vitrivr.adampro.index._
import org.vitrivr.adampro.index.structures.IndexTypes
import org.vitrivr.adampro.main.AdamContext
import org.vitrivr.adampro.query.distance.DistanceFunction
import org.vitrivr.adampro.datatypes.vector.Vector
import org.vitrivr.adampro.helpers.tracker.OperationTracker

/**
  * adamtwo
  *
  * Ivan Giangreco
  * October 2015
  */
class ECPIndexGenerator(centroidBasedLeaders: Boolean, distance: DistanceFunction, trainingSize: Option[Int])(@transient implicit val ac: AdamContext) extends IndexGenerator {
  override val indextypename: IndexTypeName = IndexTypes.ECPINDEX

  /**
    *
    * @param data raw data to index
    * @return
    */
  override def index(data: DataFrame, attribute : String)(tracker : OperationTracker): (DataFrame, Serializable) = {
    log.trace("eCP index started indexing")

    val sample = getSample(math.max(math.sqrt(data.count()).toInt, MINIMUM_NUMBER_OF_TUPLE), attribute)(data)
    val leadersBc = ac.sc.broadcast(sample.zipWithIndex.map { case (vector, idx) => IndexingTaskTuple(idx.toLong, vector.ap_indexable) }) //use own ids, not id of data
    tracker.addBroadcast(leadersBc)

    log.trace("eCP index chosen " + sample.length + " leaders")

    val minIdUDF = udf((c: DenseSparkVector) => {
      leadersBc.value.map({ l =>
        (l.ap_id, distance.apply(Vector.conv_dspark2vec(c), l.ap_indexable))
      }).minBy(_._2)._1
    })

    val indexed = data.withColumn(AttributeNames.featureIndexColumnName, minIdUDF(data(attribute)))

    import ac.spark.implicits._

    val leaders = if (centroidBasedLeaders) {
      log.trace("eCP index updating leaders, make centroid-based")

      indexed.map(r => (r.getAs[Int](AttributeNames.internalIdColumnName), r.getAs[DenseSparkVector](attribute)))
        .groupByKey(_._1)
        .mapGroups {
          case (key, values) => {
            val tmp = values.toArray.map(x => (x._2, 1))
              .reduce[(DenseSparkVector, Int)] { case (x1, x2) => (x1._1.zip(x2._1).map { case (xx1, xx2) => xx1 + xx2 }, x1._2 + x2._2) }

            val count = tmp._2
            val centroid = tmp._1.map(x => x / tmp._2.toFloat)

            key ->(centroid, count)
          }
        }
        .map(x => ECPLeader(x._1, Vector.conv_draw2vec(x._2._1), x._2._2))
        .collect.toSeq
    } else {
      val counts = indexed.groupBy(AttributeNames.featureIndexColumnName).count()
        .map { r => (r.getAs[TupleID](AttributeNames.featureIndexColumnName), r.getAs[Long]("count")) }.collect().toMap

      leadersBc.value.map(x => ECPLeader(x.ap_id, x.ap_indexable, counts.getOrElse(x.ap_id, 0)))
    }

    val meta = ECPIndexMetaData(leaders, distance)

    (indexed, meta)
  }
}

class ECPIndexGeneratorFactory extends IndexGeneratorFactory {
  /**
    * @param distance   distance function
    * @param properties indexing properties
    */
  def getIndexGenerator(distance: DistanceFunction, properties: Map[String, String] = Map[String, String]())(implicit ac: AdamContext): IndexGenerator = {
    val trainingSize = properties.get("ntraining").map(_.toInt)

    val leaderTypeDescription = properties.getOrElse("leadertype", "simple")
    val leaderType = leaderTypeDescription.toLowerCase match {
      //possibly extend with other types and introduce enum
      case "centroid" => true
      case "simple" => false
    }

    new ECPIndexGenerator(leaderType, distance, trainingSize)
  }

  /**
    *
    * @return
    */
  override def parametersInfo: Seq[ParameterInfo] = Seq(
    new ParameterInfo("ntraining", "number of training tuples", Seq[String]()),
    new ParameterInfo("leadertype", "choosing existing leader or recomputing centroid of cluster", Seq("simple", "centroid"))
  )
}