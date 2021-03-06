package org.vitrivr.adampro.catalog.catalogs

import org.vitrivr.adampro.catalog.CatalogOperator
import slick.driver.DerbyDriver.api._

/**
  * ADAMpro
  *
  * Catalog to store all entities.
  *
  * Ivan Giangreco
  * June 2016
  */
private[catalog] class EntityCatalog(tag: Tag) extends Table[(String)](tag, Some(CatalogOperator.SCHEMA), "ap_entity") {
  def entityname = column[String]("entity", O.PrimaryKey)

  /**
    * Special fields
    */
  def * = (entityname)
}
