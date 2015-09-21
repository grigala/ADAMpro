package ch.unibas.dmi.dbis.adam.main

import ch.unibas.dmi.dbis.adam.storage.components.{IndexStorage, TableStorage}
import ch.unibas.dmi.dbis.adam.storage.engine.ParquetDataStorage
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.{SparkConf, SparkContext}

/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
object  SparkStartup {
    val sparkConfig = new SparkConf().setAppName("ADAMtwo").setMaster("local[128]")
    sparkConfig.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    sparkConfig.set("spark.kryoserializer.buffer.max", "512m");
    sparkConfig.set("spark.kryoserializer.buffer", "256");
    sparkConfig.set("spark.driver.maxResultSize", "0");
    sparkConfig.set("spark.driver.memory", "9g");
    sparkConfig.set("spark.rdd.compress", "true");

    sparkConfig.registerKryoClasses(Array()) //TODO: check this!

    val sc = new SparkContext(sparkConfig)
    //val sqlContext = new SQLContext(sc)
    val sqlContext = new HiveContext(sc)

    val tableStorage: TableStorage = ParquetDataStorage
    val indexStorage: IndexStorage = ParquetDataStorage
}
