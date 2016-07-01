package ch.unibas.dmi.dbis.adam.entity

import ch.unibas.dmi.dbis.adam.catalog.CatalogOperator
import ch.unibas.dmi.dbis.adam.config.FieldNames
import ch.unibas.dmi.dbis.adam.datatypes.FieldTypes
import ch.unibas.dmi.dbis.adam.datatypes.FieldTypes._
import ch.unibas.dmi.dbis.adam.entity.Entity.EntityName
import ch.unibas.dmi.dbis.adam.exception.{EntityExistingException, EntityNotExistingException, EntityNotProperlyDefinedException, GeneralAdamException}
import ch.unibas.dmi.dbis.adam.index.Index
import ch.unibas.dmi.dbis.adam.main.AdamContext
import ch.unibas.dmi.dbis.adam.utils.Logging
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.sql.{SaveMode, DataFrame, Row}

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

/**
  * adamtwo
  *
  * Ivan Giangreco
  * October 2015
  */
//TODO: make entities singleton? lock on entity?
case class Entity(val entityname: EntityName)(@transient implicit val ac: AdamContext) extends Serializable with Logging {
  private var _data: Option[DataFrame] = None

  /**
    * Gets feature data.
    *
    * @return
    */
  def featureData: Option[DataFrame] = data(typeFilter = Some(Seq(FEATURETYPE)))

  /**
    * Gets feature column and pk column; use this for indexing purposes.
    *
    * @param attribute attribute that is indexed
    * @return
    */
  private[adam] def getIndexableFeature(attribute: String)(implicit ac: AdamContext): Option[DataFrame] = data(Some(Seq(pk.name, attribute)))

  /**
    * Gets the full entity data.
    *
    * @param nameFilter filters for names
    * @param typeFilter filters for field types
    * @return
    */
  def data(nameFilter: Option[Seq[String]] = None, typeFilter: Option[Seq[FieldType]] = None): Option[DataFrame] = {
    //cache data join
    if (_data.isEmpty) {
      val handlerData = schema().filterNot(_.pk).filter(_.storagehandler.isDefined).groupBy(_.storagehandler.get).map { case (handler, attributes) =>
        val status = handler.read(entityname)

        if (status.isFailure) {
          log.error("failure when reading data", status.failed.get)
        }

        status
      }.filter(_.isSuccess).map(_.get)

      if (handlerData.nonEmpty) {
        _data = Some(handlerData.reduce(_.join(_, pk.name)))
      } else {
        _data = None
      }
    }

    //possibly filter for current call
    if (nameFilter.isDefined || typeFilter.isDefined) {
      _data.map(_.select(schema(nameFilter, typeFilter).map(attribute => col(attribute.name)): _*))
    } else {
      _data
    }
  }

  /**
    * Caches the data.
    */
  def cache(): Unit = {
    schema().filterNot(_.pk).filter(_.storagehandler.isDefined).groupBy(_.storagehandler.get).map { case (handler, attributes) =>
      val status = handler.read(entityname)

      if (status.isFailure) {
        log.error("failure when caching data", status.failed.get)
      }

      status
    }.filter(_.isSuccess).map(_.get.cache())

    data().map(_.cache())
  }

  /**
    * Marks the data stale (e.g., if new data has been inserted to entity).
    */
  def markStale(): Unit = {
    _schema = None
    _tupleCount = None
    _data = None
    indexes.map(_.map(_.markStale()))
  }

  /**
    * Gets the primary key.
    *
    * @return
    */
  val pk = CatalogOperator.getEntityPK(entityname)


  /**
    * Returns all available indexes for the entity.
    *
    * @return
    */
  def indexes: Seq[Try[Index]] = {
    CatalogOperator.listIndexes(Some(entityname)).map(index => Index.load(index._1))
  }


  private var _tupleCount: Option[Long] = None


  /**
    * Returns number of elements in the entity.
    *
    * @return
    */
  def count: Long = {
    if (_tupleCount.isEmpty) {
      if (data().isEmpty) {
        _tupleCount = Some(0)
      } else {
        _tupleCount = Some(data().get.count())
      }
    }

    _tupleCount.get
  }


  /**
    * Gives preview of entity.
    *
    * @param k number of elements to show in preview
    * @return
    */
  def show(k: Int): Option[DataFrame] = data().map(_.limit(k))


  /**
    * Inserts data into the entity.
    *
    * @param data         data to insert
    * @param ignoreChecks whether to ignore checks
    * @return
    */
  def insert(data: DataFrame, ignoreChecks: Boolean = false): Try[Void] = {
    log.trace("inserting data into entity")

    try {
      val insertion =
        if (pk.fieldtype.equals(FieldTypes.AUTOTYPE)) {
          if (data.schema.fieldNames.contains(pk.name)) {
            return Failure(new GeneralAdamException("the field " + pk.name + " has been specified as auto and should therefore not be provided"))
          }

          val rdd = data.rdd.zipWithUniqueId.map { case (r: Row, id: Long) => Row.fromSeq(id +: r.toSeq) }
          ac.sqlContext
            .createDataFrame(
              rdd, StructType(StructField(pk.name, pk.fieldtype.datatype) +: data.schema.fields))
        } else {
          data
        }

      //TODO: check insertion schema and entity schema before trying to insert

      val handlers = insertion.schema.fields
        .map(field => schema(Some(Seq(field.name)))).filterNot(_.isEmpty).map(_.head)
        .filterNot(_.pk)
        .filter(_.storagehandler.isDefined).groupBy(_.storagehandler.get)

      handlers.foreach { case (handler, attributes) =>
        val fields = if (!attributes.exists(_.name == pk.name)) {
          attributes.+:(pk)
        } else {
          attributes
        }

        val df = insertion.select(fields.map(attribute => col(attribute.name)): _*)
        val status = handler.write(entityname, df, SaveMode.Append, Map("allowRepartitioning" -> "true"))

        if (status.isFailure) {
          throw status.failed.get
        }
      }

      Success(null)
    } catch {
      case e: Exception => Failure(e)
    } finally {
      markStale()
    }
  }

  /**
    * Drops the data of the entity.
    */
  def drop(): Unit = {
    Index.dropAll(entityname)

    schema().filter(_.storagehandler.isDefined).groupBy(_.storagehandler.get)
      .foreach { case (handler, attributes) =>
        handler.drop(entityname)
      }

    CatalogOperator.dropEntity(entityname)
  }

  /**
    * Deletes tuples from the entity
    */
  def delete(conditionExpr: String): Unit = ???


  /**
    * Returns a map of properties to the entity. Useful for printing.
    */
  def properties: Map[String, String] = {
    val lb = ListBuffer[(String, String)]()

    lb.append("schema" -> CatalogOperator.getFields(entityname).map(field => field.name + "(" + field.fieldtype.name + ")").mkString(","))
    lb.append("indexes" -> CatalogOperator.listIndexes(Some(entityname)).mkString(", "))

    lb.toMap
  }


  private var _schema: Option[Seq[AttributeDefinition]] = None

  /**
    * Schema of the entity.
    *
    * @return
    */
  def schema(nameFilter: Option[Seq[String]] = None, typeFilter: Option[Seq[FieldType]] = None): Seq[AttributeDefinition] = {
    if (_schema.isEmpty) {
      _schema = Some(CatalogOperator.getFields(entityname))
    }

    var tmpSchema = _schema.get

    if (nameFilter.isDefined) {
      tmpSchema = tmpSchema.filter(attribute => nameFilter.get.contains(attribute.name))
    }

    if (typeFilter.isDefined) {
      tmpSchema = tmpSchema.filter(attribute => typeFilter.get.map(_.name).contains(attribute.fieldtype.name))
    }

    tmpSchema
  }

  override def equals(that: Any): Boolean =
    that match {
      case that: Entity =>
        this.entityname.equals(that.entityname)
      case _ => false
    }

  override def hashCode: Int = entityname.hashCode
}

object Entity extends Logging {
  type EntityName = EntityNameHolder

  /**
    * Check if entity exists. Note that this only checks the catalog; the entity may still exist in the file system.
    *
    * @param entityname name of entity
    * @return
    */
  def exists(entityname: EntityName): Boolean = CatalogOperator.existsEntity(entityname)

  /**
    * Creates an entity.
    *
    * @param entityname         name of the entity
    * @param creationAttributes attributes of entity
    * @param ifNotExists        if set to true and the entity exists, the entity is just returned; otherwise an error is thrown
    * @return
    */
  def create(entityname: EntityName, creationAttributes: Seq[AttributeDefinition], ifNotExists: Boolean = false)(implicit ac: AdamContext): Try[Entity] = {
    try {
      //checks
      if (exists(entityname)) {
        if (!ifNotExists) {
          return Failure(EntityExistingException())
        } else {
          return load(entityname)
        }
      }

      if (creationAttributes.isEmpty) {
        return Failure(EntityNotProperlyDefinedException("Entity " + entityname + " will have no attributes"))
      }

      FieldNames.reservedNames.foreach { reservedName =>
        if (creationAttributes.map(_.name).contains(reservedName)) {
          return Failure(EntityNotProperlyDefinedException("Entity defined with field " + reservedName + ", but name is reserved"))
        }
      }

      if (creationAttributes.map(_.name).distinct.length != creationAttributes.length) {
        return Failure(EntityNotProperlyDefinedException("Entity defined with duplicate fields."))
      }

      val allowedPkTypes = FieldTypes.values.filter(_.pk)

      if (creationAttributes.count(_.pk) > 1) {
        return Failure(EntityNotProperlyDefinedException("Entity defined with more than one primary key"))
      } else if (!creationAttributes.exists(_.pk)) {
        return Failure(EntityNotProperlyDefinedException("Entity defined has no primary key."))
      } else if (!creationAttributes.filter(_.pk).forall(field => allowedPkTypes.contains(field.fieldtype))) {
        return Failure(EntityNotProperlyDefinedException("Entity defined needs a " + allowedPkTypes.map(_.name).mkString(", ") + " primary key"))
      }

      if (creationAttributes.count(_.fieldtype == AUTOTYPE) > 1) {
        return Failure(EntityNotProperlyDefinedException("Too many auto attributes defined."))
      } else if (creationAttributes.count(_.fieldtype == AUTOTYPE) > 0 && !creationAttributes.filter(_.pk).forall(_.fieldtype == AUTOTYPE)) {
        return Failure(EntityNotProperlyDefinedException("Auto type only allowed in primary key."))
      }

      CatalogOperator.createEntity(entityname, creationAttributes)

      val pk = creationAttributes.filter(_.pk)

      if (creationAttributes.forall(_.pk)) {
        //only pk attribute is available
        creationAttributes.filter(_.storagehandler.isDefined).groupBy(_.storagehandler.get).foreach {
          case (handler, attributes) =>
            val status = handler.create(entityname, attributes.++:(pk))
            if (status.isFailure) {
              throw status.failed.get
            }
        }
      } else {
        val tmp = creationAttributes.filterNot(_.pk).filter(_.storagehandler.isDefined).groupBy(_.storagehandler.get)

        creationAttributes.filterNot(_.pk).filter(_.storagehandler.isDefined).groupBy(_.storagehandler.get).foreach {
          case (handler, attributes) =>
            val status = handler.create(entityname, attributes.++:(pk))
            if (status.isFailure) {
              throw status.failed.get
            }
        }
      }

      Success(Entity(entityname)(ac))
    } catch {
      case e: Exception =>
        //TODO: possibly drop what has been already created
        Failure(e)
    }
  }

  /**
    * Drops an entity.
    *
    * @param entityname name of entity
    * @param ifExists   if set to true, no error is raised if entity does not exist
    * @return
    */
  def drop(entityname: EntityName, ifExists: Boolean = false)(implicit ac: AdamContext): Try[Void] = {
    if (!exists(entityname)) {
      if (!ifExists) {
        return Failure(EntityNotExistingException())
      } else {
        return Success(null)
      }
    }

    Entity.load(entityname).get.drop()
    EntityLRUCache.invalidate(entityname)

    Success(null)
  }


  /**
    * Loads an entity.
    *
    * @param entityname name of entity
    * @return
    */
  def load(entityname: EntityName, cache: Boolean = false)(implicit ac: AdamContext): Try[Entity] = {
    if (!exists(entityname)) {
      return Failure(EntityNotExistingException("Entity " + entityname + " is not existing."))
    }

    val entity = EntityLRUCache.get(entityname)

    if (cache) {
      entity.map(_.cache())
    }

    entity
  }


  /**
    * Loads the entityname metadata without loading the data itself yet.
    *
    * @param entityname name of entity
    * @return
    */
  private[entity] def loadEntityMetaData(entityname: EntityName)(implicit ac: AdamContext): Try[Entity] = {
    if (!exists(entityname)) {
      return Failure(EntityNotExistingException())
    }

    try {
      Success(Entity(entityname)(ac))
    } catch {
      case e: Exception => Failure(e)
    }
  }


  /**
    * Lists names of all entities.
    *
    * @return name of entities
    */
  def list: Seq[EntityName] = CatalogOperator.listEntities()
}