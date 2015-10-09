package ch.unibas.dmi.dbis.adam.storage.engine

import java.io._
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import ch.unibas.dmi.dbis.adam.main.{SparkStartup, Startup}
import ch.unibas.dmi.dbis.adam.storage.components.LazyTableStorage
import ch.unibas.dmi.dbis.adam.table.Table.TableName
import ch.unibas.dmi.dbis.adam.table.Tuple._
import ch.unibas.dmi.dbis.adam.table.{LazyTable, Table}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SaveMode}
import org.iq80.leveldb._
import org.iq80.leveldb.impl.Iq80DBFactory._

import scala.collection._
import scala.collection.convert.decorateAsScala._
import scala.collection.mutable.ListBuffer



/**
 * adamtwo
 *
 * Ivan Giangreco
 * October 2015
 */


object LevelDBDataStorage extends LazyTableStorage {
  protected case class DBStatus(db : DB, var locks : Int)

  val config = Startup.config
  val databases: concurrent.Map[TableName, DBStatus] = new ConcurrentHashMap[TableName, DBStatus]().asScala

  /**
   *
   * @param tablename
   * @param options
   * @return
   */
  private def openConnection(tablename : TableName, options : Options): DB ={
    databases.synchronized({
      if(databases.contains(tablename)){
       val dbStatus = databases.get(tablename)
      dbStatus.synchronized({
      dbStatus.get.locks += 1
      })
      return dbStatus.get.db
    } else {
        val db: DB = factory.open(new File(config.dataPath + "/" + tablename + ".leveldb"), options)
        val dbStatus = DBStatus(db, 1)
        databases.putIfAbsent(tablename, dbStatus)
      }
      return databases.get(tablename).get.db
    }
    )
  }

  /**
   *
   * @param tablename
   */
  private def closeConnection(tablename : TableName): Unit = {
    if (databases.contains(tablename)) {
      databases.synchronized({
        val status = databases.get(tablename).get
        status.synchronized({
          status.locks -= 1

          if (status.locks == 0) {
            status.db.close()
            databases.remove(tablename)
          }
        })

      })
    }
  }

  /**
   *
   * @param tablename
   * @return
   */
  override def readTable(tablename: TableName): Table = {
    LazyTable(tablename,  this)
  }

  /**
   *
   * @param tablename
   * @return
   */
  override def readFullTable(tablename: TableName): DataFrame = {
    val options = new Options()
    options.createIfMissing(true)

    val db = openConnection(tablename, options)

    //TODO: error?

    val it = db.iterator()

    try {
      val lb = ListBuffer[Row]()

      while(it.hasNext) {
        val next = it.peekNext
        val row = Row(asLong(next.getKey), asFloatArray(next.getValue))
        lb.+=(row)
      }

      val rdd = SparkStartup.sc.parallelize(lb.toSeq)

      val schema = StructType(
        List(
          StructField("id", LongType, false),
          StructField("feature", ArrayType(FloatType), false)
        )
      )

      SparkStartup.sqlContext.createDataFrame(rdd, schema)
    } finally {
      // Make sure you close the db to shutdown the
      // database and avoid resource leaks.
      it.close()

      closeConnection(tablename)
    }
  }


  /**
   *
   * @param tablename
   * @param filter
   * @return
   */
  override def readFilteredTable(tablename: TableName, filter: Predef.Set[TupleID]): DataFrame  = {
    val options = new Options()
    options.createIfMissing(true)

    val db = openConnection(tablename, options)

    try {
      val data = filter.par.map(f => {
        (f, asFloatArray(db.get(bytes(f))))
      }).map(r => Row(r._1, r._2)).toSeq.seq

      val rdd = SparkStartup.sc.parallelize(data)

      val schema = StructType(
        List(
          StructField("id", LongType, false),
          StructField("feature", ArrayType(FloatType), false)
        )
      )

      SparkStartup.sqlContext.createDataFrame(rdd, schema)
    } finally {
      // Make sure you close the db to shutdown the
      // database and avoid resource leaks.
      closeConnection(tablename)
    }
  }


  /**
   *
   * @param tablename
   */
  override def dropTable(tablename: TableName): Unit = {
    factory.destroy(new File(config.dataPath + "/" + tablename + ".leveldb"), new Options())
  }

  /**
   *
   * @param tablename
   * @param df
   * @param mode
   */
  override def writeTable(tablename: TableName, df: DataFrame, mode: SaveMode): Unit = {
    val options = new Options()
    options.createIfMissing(true)
    val db = factory.open(new File(config.dataPath + "/" + tablename + ".leveldb"), options)

    try {
      val it = df.rdd.toLocalIterator.sliding(50000, 50000)

      while (it.hasNext) {
        val data = it.next()

        val batch = db.createWriteBatch()
        var i = 0

        while (i < data.length) {
          val t = data(i)
          batch.put(bytes(t.tid), bytes(t.value))
          i += 1
        }

        db.write(batch)
      }

    } finally {
      // Make sure you close the db to shutdown the
      // database and avoid resource leaks.
      db.close();
    }
  }

  /**
   *
   * @param value
   * @return
   */
  private def bytes(value: Long) = {
    ByteBuffer.allocate(8).putLong(value).array();
  }

  /**
   *
   * @param v
   * @return
   */
  private def asLong(v: Array[Byte]): Long = {
    val buffer = ByteBuffer.allocate(8);
    buffer.put(v);
    buffer.flip();
    buffer.getLong();
  }

  /**
   *
   * @param v
   * @return
   */
  private def asFloatArray(v: Array[Byte]): Seq[Float] = {
    if (v != null) {
      val dbuf = java.nio.FloatBuffer.allocate(v.length / 4)
      dbuf.put(java.nio.ByteBuffer.wrap(v).asFloatBuffer())
      dbuf.array.toSeq
    } else {
      Seq(0.toFloat)
    }
  }

  /**
   *
   * @param values
   * @return
   */
  private def bytes(values: Seq[Float]) = {
    val bbuf = java.nio.ByteBuffer.allocate(4 * values.length)
    bbuf.asFloatBuffer.put(java.nio.FloatBuffer.wrap(values.toArray))
    val b = bbuf.array
    b
  }

}
