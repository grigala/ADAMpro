package ch.unibas.dmi.dbis.adam.index.structures.vectorapproximation

import ch.unibas.dmi.dbis.adam.datatypes.Feature._
import ch.unibas.dmi.dbis.adam.datatypes.bitString.BitString
import ch.unibas.dmi.dbis.adam.index.Index._
import ch.unibas.dmi.dbis.adam.index.structures.vectorapproximation.VectorApproximationIndex.{Bounds, Marks}
import ch.unibas.dmi.dbis.adam.index.structures.vectorapproximation.results.VectorApproximationResultHandler
import ch.unibas.dmi.dbis.adam.index.structures.vectorapproximation.signature.{VariableSignatureGenerator, FixedSignatureGenerator}
import ch.unibas.dmi.dbis.adam.index.{BitStringIndexTuple, Index}
import ch.unibas.dmi.dbis.adam.main.SparkStartup
import ch.unibas.dmi.dbis.adam.query.distance.Distance._
import ch.unibas.dmi.dbis.adam.query.distance.NormBasedDistanceFunction
import ch.unibas.dmi.dbis.adam.table.Table._
import ch.unibas.dmi.dbis.adam.table.Tuple._
import com.timgroup.iterata.ParIterator.Implicits._
import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame

import scala.collection.immutable.HashSet

/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
class VectorApproximationIndex(val indexname : IndexName, val tablename : TableName, protected val indexdata: DataFrame, private val indexMetaData: VectorApproximationIndexMetaData)
  extends Index[BitStringIndexTuple] with Serializable {
  override val indextypename: IndexTypeName = indexMetaData.signatureGenerator match {
    case fsg : FixedSignatureGenerator => "fva"
    case vsg : VariableSignatureGenerator => "nva"
    case _ => "va"
  }
  override val precise = true

  /**
   *
   * @return
   */
  override protected def indexToTuple : RDD[BitStringIndexTuple] = {
    indexdata
      .map { tuple =>
      BitStringIndexTuple(tuple.getLong(0), tuple.getAs[BitString[_]](1))
    }
  }

  /**
   *
   */
  override def scan(q: WorkingVector, options: Map[String, String], filter : Option[HashSet[TupleID]], queryID : String): HashSet[TupleID] = {
    val k = options("k").toInt
    val norm = options("norm").toInt
    
    val (lbounds, ubounds) = computeBounds(q, indexMetaData.marks, new NormBasedDistanceFunction(norm))

    SparkStartup.sc.setLocalProperty("spark.scheduler.pool", "index")
    SparkStartup.sc.setJobGroup(queryID, indextypename, true)
    val results = SparkStartup.sc.runJob(getIndexTuples(filter), (context : TaskContext, tuplesIt : Iterator[BitStringIndexTuple]) => {
      val localRh = new VectorApproximationResultHandler(k, lbounds, ubounds, indexMetaData.signatureGenerator)
      localRh.offerIndexTuple(tuplesIt.par())
      localRh.results.toSeq
    }).flatten

    val globalResultHandler = new VectorApproximationResultHandler(k)
    globalResultHandler.offerResultElement(results.iterator)
    val ids = globalResultHandler.results.map(x => x.tid).toList

    HashSet(ids : _*)
  }

  /**
   *
   * @param q
   * @param marks
   * @param distance
   * @return
   */
  private[this] def computeBounds(q: WorkingVector, marks: => Marks, @inline distance: NormBasedDistanceFunction): (Bounds, Bounds) = {
    val lbounds, ubounds = Array.tabulate(marks.length)(i => Array.ofDim[Distance](marks(i).length - 1))

     var i = 0
     while(i < marks.length) {
        val dimMarks = marks(i)
        val fvi = q(i)

        var j = 0
        val it = dimMarks.iterator.sliding(2).withPartial(false)

        while(it.hasNext){
          val dimMark = it.next()

          lazy val d0fv1 = distance(dimMark(0), fvi)
          lazy val d1fv1 = distance(dimMark(1), fvi)

          if (fvi < dimMark(0)) {
            lbounds(i)(j) = d0fv1
          } else if (fvi > dimMark(1)) {
            lbounds(i)(j) = d1fv1
          }

          if (fvi <= (dimMark(0) + dimMark(1)) / 2.toFloat) {
            ubounds(i)(j) = d1fv1
          } else {
            ubounds(i)(j) = d0fv1
          }

          j += 1
        }

         i += 1
    }

    (lbounds, ubounds)
  }


  /**
   *
   */
  override private[index] def getMetadata(): Serializable = {
    indexMetaData
  }
}

object VectorApproximationIndex {
  type Marks = Seq[Seq[VectorBase]]
  type Bounds = Array[Array[Distance]]

  def apply(indexname : IndexName, tablename : TableName, data: DataFrame, meta : Any) : VectorApproximationIndex = {
    val indexMetaData = meta.asInstanceOf[VectorApproximationIndexMetaData]
    new VectorApproximationIndex(indexname, tablename, data, indexMetaData)
  }
}