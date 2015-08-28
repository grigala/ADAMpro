package org.apache.spark.adam

import org.apache.spark.util.random.SamplingUtils

/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
object ADAMSamplingUtils {
  def computeFractionForSampleSize(sampleSizeLowerBound: Int, total: Long, withReplacement: Boolean): Double = {
    SamplingUtils.computeFractionForSampleSize(sampleSizeLowerBound, total, withReplacement)
  }
}
