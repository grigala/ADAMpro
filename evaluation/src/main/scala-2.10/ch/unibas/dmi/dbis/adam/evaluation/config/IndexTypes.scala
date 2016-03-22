package ch.unibas.dmi.dbis.adam.evaluation.config


import ch.unibas.dmi.dbis.adam.http._

/**
  * adampro
  *
  * Ivan Giangreco
  * March 2016
  */
object IndexTypes {

  sealed abstract class IndexType(val name: String, val indextype: grpc.adam.IndexType)

  case object ECPINDEX extends IndexType("ecp", grpc.adam.IndexType.ecp)

  case object LSHINDEX extends IndexType("lsh", grpc.adam.IndexType.lsh)

  case object SHINDEX extends IndexType("sh", grpc.adam.IndexType.sh)

  case object VAFINDEX extends IndexType("vaf", grpc.adam.IndexType.vaf)

  case object VAVINDEX extends IndexType("vav", grpc.adam.IndexType.vav)


  /**
    *
    */
  val values = Seq(ECPINDEX, LSHINDEX, SHINDEX, VAFINDEX, VAVINDEX)

  /**
    *
    * @param s
    * @return
    */
  def withName(s : String) : Option[IndexType] = values.map(value => value.name -> value).toMap.get(s)

  /**
    *
    * @param indextype
    * @return
    */
  def withIndextype(indextype: grpc.adam.IndexType) : Option[IndexType] = values.map(value => value.indextype -> value).toMap.get(indextype)
}
