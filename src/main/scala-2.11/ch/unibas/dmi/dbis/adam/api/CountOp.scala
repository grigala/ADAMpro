package ch.unibas.dmi.dbis.adam.api

import ch.unibas.dmi.dbis.adam.entity.Entity
import ch.unibas.dmi.dbis.adam.entity.Entity._

/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
object CountOp {
  /**
   *
   * @param tablename
   */
  def apply(tablename : EntityName): Long = {
    val table = Entity.retrieveEntity(tablename)
    table.count
  }
}