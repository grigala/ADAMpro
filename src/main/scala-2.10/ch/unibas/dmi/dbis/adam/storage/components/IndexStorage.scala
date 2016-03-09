package ch.unibas.dmi.dbis.adam.storage.components

import ch.unibas.dmi.dbis.adam.entity.Entity._
import ch.unibas.dmi.dbis.adam.entity.Tuple._
import ch.unibas.dmi.dbis.adam.index.Index.IndexName
import org.apache.spark.sql.DataFrame

/**
  * adamtwo
  *
  * Ivan Giangreco
  * August 2015
  */
trait IndexStorage {
  /**
    * Create the entity in the index storage (when creating the index)
    *
    * @param entityname
    * @param data
    * @return
    */
  def create(entityname: EntityName, data: DataFrame) = write(entityname, data)

  /**
    * Read index from the index storage.
    *
    * @param indexName
    * @param filter
    * @return
    */
  def read(indexName: IndexName, filter: Option[scala.collection.Set[TupleID]] = None): DataFrame

  /**
    * Write index to the index storage.
    * @param indexName
    * @param index
    * @return true on success
    */
  def write(indexName: IndexName, index: DataFrame): Boolean

  /**
    * Drop the index from the index storage.
    * @param indexName
    * @return true on success
    */
  def drop(indexName: IndexName): Boolean
}
