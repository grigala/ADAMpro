package org.vitrivr.adampro.helpers.partition

import org.vitrivr.adampro.catalog.CatalogOperator
import org.vitrivr.adampro.config.FieldNames
import org.vitrivr.adampro.datatypes.feature.Feature.FeatureVector
import org.vitrivr.adampro.entity.{Entity, EntityNameHolder}
import org.vitrivr.adampro.exception.GeneralAdamException
import org.vitrivr.adampro.index.structures.IndexTypes
import org.vitrivr.adampro.index.structures.ecp.ECPIndexMetaData
import org.vitrivr.adampro.index.{Index, IndexingTaskTuple}
import org.vitrivr.adampro.main.AdamContext
import org.vitrivr.adampro.query.distance.DistanceFunction
import org.vitrivr.adampro.utils.Logging
import org.apache.spark.Partitioner
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.util.random.Sampling

/**
  * ADAMpar
  *
  * Silvan Heller
  * June 2016
  */
class ECPPartitioner(meta: ECPPartitionerMetaData, indexmeta: ECPIndexMetaData) extends Partitioner with Logging {

  override def numPartitions: Int = meta.getNoPart

  /**
    * Assigns a tuple to the closest partitioning leader
    */
  override def getPartition(key: Any): Int = {
    val leaderAssignment = key.asInstanceOf[Int]
    val leader = indexmeta.leaders.find(_.id == leaderAssignment).get
    val part = meta.getLeaders.sortBy(el => meta.getDistanceFunction(el.feature, leader.feature)).head.id.asInstanceOf[Int]
    part
  }
}

object ECPPartitioner extends CustomPartitioner with Logging with Serializable {

  override def partitionerName: PartitionerChoice.Value = PartitionerChoice.ECP

  /**
    * This uses eCP on the eCP-leaders.
    * Improvements to be done: Use K-Means, partition size balancing, soft-assignment
    */
  def trainLeaders(indexmeta: ECPIndexMetaData, nPart: Int)(implicit ac: AdamContext): Array[IndexingTaskTuple[Int]] = {
    val trainingSize = nPart
    val fraction = Sampling.computeFractionForSampleSize(trainingSize, indexmeta.leaders.size, withReplacement = false)
    val leaders = ac.sc.parallelize(indexmeta.leaders)
    val traindata = leaders.sample(withReplacement = false, fraction = fraction).collect()
    traindata.take(nPart).zipWithIndex.map(f => IndexingTaskTuple[Int](f._2, f._1.feature))
  }

  /**
    * Repartition Data based on the eCP-Idea. Assigns each eCP-leader to a cluster which is chosen like in the eCP-Method
    * Data points are then assigned partitions based on their ecp-leaders. Distance comparison would be cleaner, but the ecp-index only stores ecp-leaders
    *
    * @param data        DataFrame you want to partition
    * @param cols        Irrelevant here
    * @param indexName   Will be used to store partitioner information in the catalog
    * @param nPartitions how many partitions shall be created
    * @return the partitioned DataFrame
    */
  override def apply(data: DataFrame, cols: Option[Seq[String]], indexName: Option[EntityNameHolder], nPartitions: Int, options: Map[String, String] = Map[String, String]())(implicit ac: AdamContext): DataFrame = {

    //loads the first ECPIndex
    val index = try {
      Entity.load(Index.load(indexName.get).get.entityname).get.indexes.find(f => f.get.indextypename == IndexTypes.ECPINDEX).get.get
    } catch {
      case e: java.util.NoSuchElementException => throw new GeneralAdamException("Repartitioning Failed because ECP Index was not created")
    }
    val joinDF = index.getData().get.withColumnRenamed(FieldNames.featureIndexColumnName, FieldNames.partitionKey)
    val joinedDF = data.join(joinDF, index.pk.name)
    log.debug("repartitioning ")

    val indexmeta = CatalogOperator.getIndexMeta(index.indexname).get.asInstanceOf[ECPIndexMetaData]
    val leaders = trainLeaders(indexmeta, nPartitions)

    CatalogOperator.dropPartitioner(indexName.get)
    CatalogOperator.createPartitioner(indexName.get, nPartitions, new ECPPartitionerMetaData(nPartitions, leaders, indexmeta.distance), ECPPartitioner)
    //repartition
    val partitioner = new ECPPartitioner(new ECPPartitionerMetaData(nPartitions, leaders, indexmeta.distance), indexmeta)
    val repartitioned: RDD[(Any, Row)] = joinedDF.map(r => (r.getAs[Any](FieldNames.partitionKey), r)).partitionBy(partitioner)
    val reparRDD = repartitioned.mapPartitions((it) => {
      it.map(f => f._2)
    }, true)
    ac.sqlContext.createDataFrame(reparRDD, joinedDF.schema)
  }

  /** Returns the partitions to be queried for a given Feature vector
    * Simply compares to partition leaders
    * */
  override def getPartitions(q: FeatureVector, dropPercentage: Double, indexName: EntityNameHolder)(implicit ac: AdamContext): Seq[Int] = {
    val meta = CatalogOperator.getPartitionerMeta(indexName).get.asInstanceOf[ECPPartitionerMetaData]
    meta.getLeaders.sortBy(f => meta.getDistanceFunction(q, f.feature)).dropRight((meta.getNoPart * dropPercentage).toInt).map(_.id.toString.toInt)
  }
}

class ECPPartitionerMetaData(nPart: Int, leaders: Seq[IndexingTaskTuple[_]], distance: DistanceFunction) extends Serializable {
  def getNoPart: Int = nPart

  def getLeaders: Seq[IndexingTaskTuple[_]] = leaders

  def getDistanceFunction: DistanceFunction = distance
}