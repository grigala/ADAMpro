package org.vitrivr.adampro.api

import org.apache.spark.sql.DataFrame
import org.vitrivr.adampro.entity.Entity
import org.vitrivr.adampro.entity.Entity._
import org.vitrivr.adampro.exception.GeneralAdamException
import org.vitrivr.adampro.grpc.grpc.IndexType
import org.vitrivr.adampro.helpers.tracker.OperationTracker
import org.vitrivr.adampro.index.partition.{PartitionMode, PartitionerChoice}
import org.vitrivr.adampro.index.Index._
import org.vitrivr.adampro.index.structures.IndexTypes
import org.vitrivr.adampro.index.structures.IndexTypes.IndexType
import org.vitrivr.adampro.index.{Index, IndexPartitioner}
import org.vitrivr.adampro.main.{AdamContext, SparkStartup}
import org.vitrivr.adampro.query.distance.DistanceFunction

import scala.util.{Failure, Success, Try}

/**
  * adamtwo
  *
  *
  * Ivan Giangreco
  * August 2015
  */
object IndexOp extends GenericOp {
  /**
    * Lists all indexes.
    *
    * @param entityname name of entity
    */
  def list(entityname: EntityName)(implicit ac: AdamContext): Try[Seq[(IndexName, String, IndexTypeName)]] = {
    execute("list indexes for " + entityname) {
      Success(Entity.load(entityname).get.indexes.filter(_.isSuccess).map(_.get).map(index => (index.indexname, index.attribute, index.indextypename)))
    }
  }

  /**
    * Lists all indexes.
    *
    */
  def list()(implicit ac: AdamContext): Try[Seq[(IndexName, String, IndexTypeName)]] = {
    execute("list all indexes") {
      val indexes = SparkStartup.catalogOperator.listIndexes()

      if (indexes.isSuccess) {
        val res = indexes.get.map(indexname => (indexname, SparkStartup.catalogOperator.getIndexAttribute(indexname).get, SparkStartup.catalogOperator.getIndexTypeName(indexname).get))
        Success(res)
      } else {
        Failure(indexes.failed.get)
      }
    }
  }


  /**
    * Creates an index.
    *
    * @param entityname    name of entity
    * @param attribute     name of attribute
    * @param indextypename index type to use for indexing
    * @param distance      distance function to use
    * @param properties    further index specific properties
    */
  def create(entityname: EntityName, attribute: String, indextypename: IndexTypeName, distance: DistanceFunction, properties: Map[String, String] = Map())(tracker : OperationTracker = new OperationTracker())(implicit ac: AdamContext): Try[Index] = {
    execute("create index for " + entityname) {
      val res = Index.createIndex(Entity.load(entityname).get, attribute, indextypename, distance, properties)(tracker)
      tracker.cleanAll()
      res
    }
  }

  /**
    * Creates indexes of all available types.
    *
    * @param entityname name of entity
    * @param attribute  name of attribute
    * @param distance   distance function to use
    * @param properties further index specific properties
    */
  def generateAll(entityname: EntityName, attribute: String, distance: DistanceFunction, properties: Map[String, String] = Map())(tracker : OperationTracker = new OperationTracker())(implicit ac: AdamContext): Try[Seq[IndexName]] = {
    execute("create missing indexes for " + entityname) {
      val existingIndexTypes = list(entityname).get.map(_._3)
      val allIndexTypes = IndexTypes.values

      val indexes = (allIndexTypes filterNot (existingIndexTypes contains)).map { indextype =>
        IndexOp.create(entityname, attribute, indextype, distance, properties)(tracker)
      }

      //check and possibly clean up
      if (indexes.forall(_.isSuccess)) {
        //all indexes were created, return
        return Success(indexes.map(_.get.indexname))
      }

      log.error("not all indexes were created")

      //not all indexes were created, delete the ones that were successfull too
      indexes
        .filter(_.isSuccess)
        .map(_.get.indexname)
        .foreach {
          indexname =>
            Index.drop(indexname)
        }

      return Failure(new GeneralAdamException("some indexes were not created properly."))
    }
  }

  /**
    * Checks if index exists
    *
    * @param indexname name of index
    * @return
    */
  def exists(indexname: IndexName)(implicit ac: AdamContext): Try[Boolean] = {
    execute("check index " + indexname + " exists operation") {
      Success(Index.exists(indexname))
    }
  }


  /**
    * Checks if index exists
    *
    * @param entityname    name of entity
    * @param attribute     name of attribute
    * @param indextypename index type to use for indexing
    * @return
    */
  def exists(entityname: EntityName, attribute: String, indextypename: IndexTypeName)(implicit ac: AdamContext): Try[Boolean] = {
    execute("check index for " + entityname + "(" + attribute + ")" + " of type " + indextypename + " exists operation") {
      Success(Index.exists(entityname, attribute, indextypename))
    }
  }


  /**
    * Loads the index into cache for faster processing.
    *
    * @param indexname name of index
    * @return
    */
  def cache(indexname: IndexName)(implicit ac: AdamContext): Try[Index] = {
    execute("cache index " + indexname + " operation") {
      Index.load(indexname, cache = true)
    }
  }

  /**
    * Returns properties of index.
    *
    * @param indexname name of index
    * @param options   possible options for operation
    */
  def properties(indexname: IndexName, options: Map[String, String] = Map())(implicit ac: AdamContext): Try[Map[String, String]] = {
    execute("get properties for " + indexname) {
      val index = Index.load(indexname)

      if (index.isFailure) {
        return Failure(index.failed.get)
      }

      Success(index.get.propertiesMap(options))
    }
  }


  /**
    * Repartitions the index.
    *
    * @param indexname   name of index
    * @param nPartitions number of partitions
    * @param attribute   attributes to partition after
    * @param mode        partition mode (e.g., create new index, replace current index, etc.)
    * @param partitioner partitioner to use
    * @return
    */
  def partition(indexname: IndexName, nPartitions: Int, joins: Option[DataFrame], attribute: Option[AttributeName], mode: PartitionMode.Value, partitioner: PartitionerChoice.Value = PartitionerChoice.SPARK, options: Map[String, String] = Map[String, String]())(implicit ac: AdamContext): Try[Index] = {
    execute("repartition index " + indexname + " operation") {
      IndexPartitioner(Index.load(indexname).get, nPartitions, joins, attribute, mode, partitioner, options)
    }
  }

  /**
    * Drops an index.
    *
    * @param indexname name of index
    * @return
    */
  def drop(indexname: IndexName)(implicit ac: AdamContext): Try[Void] = {
    execute("drop index " + indexname + " operation") {
      Index.drop(indexname)
    }
  }
}
