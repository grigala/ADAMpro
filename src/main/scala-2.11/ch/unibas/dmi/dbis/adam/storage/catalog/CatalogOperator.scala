package ch.unibas.dmi.dbis.adam.storage.catalog

import ch.unibas.dmi.dbis.adam.data.IndexMeta
import ch.unibas.dmi.dbis.adam.exception.{IndexExistingException, TableExistingException, TableNotExistingException}
import ch.unibas.dmi.dbis.adam.index.Index.IndexName
import ch.unibas.dmi.dbis.adam.main.Startup
import ch.unibas.dmi.dbis.adam.table.Table.TableName
import org.json4s._
import org.json4s.native.Serialization
import org.json4s.native.Serialization._
import slick.driver.H2Driver.api._
import slick.jdbc.meta.MTable

import scala.concurrent.Await
import scala.concurrent.duration._


/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
object CatalogOperator {
  private val config = Startup.config
  //TODO change path to config...
  private val db = Database.forURL("jdbc:h2:./data/catalog", driver="org.h2.Driver")

  //generate catalog tables in the beginning if not already existent
  val tableList = Await.result(db.run(MTable.getTables), 1.seconds).toList.map(x => x.name.name)
  Catalog().filterNot(mdd => tableList.contains(mdd._1)).foreach(mdd => {
    db.run(mdd._2.schema.create)
  })

  implicit val formats = Serialization.formats(NoTypeHints)


  private val tables = TableQuery[TablesCatalog]
  private val indexes = TableQuery[IndexesCatalog]

  /**
   *
   * @param tablename
   */
  def createTable(tablename : TableName): Unit ={
    if(existsTable(tablename)){
      throw new TableExistingException()
    }

    val setup = DBIO.seq(
      tables.+=(tablename)
    )
    db.run(setup)
  }


  /**
   *
   * @param tablename
   * @return
   */
  def existsTable(tablename : TableName): Boolean ={
    val query = tables.filter(_.tablename === tablename).length.result
    val count = Await.result(db.run(query), 5.seconds)

    (count > 0)
  }

  /**
   *
   * @return
   */
  def listTables() : List[TableName] = {
    val query = tables.map(_.tablename).result
    Await.result(db.run(query), 5.seconds).toList
  }

  /**
   *
   * @param indexname
   * @return
   */
  def existsIndex(indexname : IndexName): Boolean = {
    val query = indexes.filter(_.tablename === indexname).length.result
    val count = Await.result(db.run(query), 5.seconds)

    (count > 0)
  }

  /**
   *
   * @param indexname
   * @param tablename
   * @param indexmeta
   */
  def createIndex(indexname : IndexName, tablename : TableName, indexmeta : IndexMeta): Unit ={
    if(!existsTable(tablename)){
      throw new TableNotExistingException()
    }

    if(existsIndex(indexname)){
      throw new IndexExistingException()
    }

    val json =  write(indexmeta.map)

    val setup = DBIO.seq(
      indexes.+=((indexname, tablename, json))
    )
    db.run(setup)
  }

  /**
   *
   * @param tablename
   */
  def getIndexes(tablename : TableName): Seq[IndexName] = {
    val query = indexes.filter(_.tablename === tablename).map(_.indexname).result
    Await.result(db.run(query), 5.seconds).toList
  }

  /**
   *
   * @param indexname
   * @return
   */
  def getIndexMeta(indexname : IndexName) : IndexMeta = {
    val query = indexes.filter(_.indexname === indexname).map(_.indexmeta).result.head

    val json = Await.result(db.run(query), 5.seconds)
    new IndexMeta(read[Map[String, String]](json))
  }

  /**
   *
   * @param indexname
   * @return
   */
  def getIndexTableName(indexname : IndexName) : TableName = {
    val query = indexes.filter(_.indexname === indexname).map(_.tablename).result.head
    Await.result(db.run(query), 5.seconds)
  }
}
