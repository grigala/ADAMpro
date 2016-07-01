package ch.unibas.dmi.dbis.adam.catalog.catalogs

import ch.unibas.dmi.dbis.adam.catalog.CatalogOperator
import slick.driver.PostgresDriver.api._

/**
  * ADAMpro
  *
  * Ivan Giangreco
  * June 2016
  */
private[catalog] class AttributeWeightCatalog(tag: Tag) extends Table[(String, String, Float)](tag, Some(CatalogOperator.SCHEMA), "ap_attributeweight") {
  def entityname = column[String]("entityname")

  def attributename = column[String]("attributename")

  def weight = column[Float]("weight")

  /**
    * Special fields
    */
  def pk = primaryKey("attributeweight_pk", (entityname, attributename))

  def * = (entityname, attributename, weight)

  def attribute = foreignKey("attributeweight_attribute_fk", (entityname, attributename), TableQuery[AttributeCatalog])(t => (t.entityname, t.attributename), onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}
