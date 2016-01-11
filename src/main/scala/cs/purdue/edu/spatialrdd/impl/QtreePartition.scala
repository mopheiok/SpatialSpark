package cs.purdue.edu.spatialrdd.impl

import cs.purdue.edu.spatialindex.quatree.QTree
import cs.purdue.edu.spatialindex.rtree._
import cs.purdue.edu.spatialrdd.SpatialRDDPartition
import org.apache.spark.Logging

import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

/**
 * Created by merlin on 11/27/15.
 */
class QtreePartition [K, V]
(protected val tree: QTree[V])
(
  override implicit val kTag: ClassTag[K],
  override implicit val vTag: ClassTag[V]
  )
  extends SpatialRDDPartition[K,V] with Logging{


  override def size: Long = tree.size.asInstanceOf[Long]

  override def apply(k: K): Option[V] = {
    tree.searchPoint(Util.toPoint(k)) match
    {
      case null=>None
      case x:Entry[V]=>Some(x.value)
    }

  }

  def isDefined(k: K): Boolean = tree.searchPoint(Util.toPoint(k)) != null

  override def iterator: Iterator[(K, V)] ={

    this.tree.entries.map(
      kvs=>(kvs.geom.asInstanceOf[K],kvs.value))
  }

  /**
   * constructor for a new rtree partition
   */
  protected def withMap(map: QTree[V]): QtreePartition[K, V] = {

    new QtreePartition(map)

  }

  /**
   * Gets the values corresponding to the specified keys, if any. those keys can be the two dimensional object
   */
  override def multiget(ks: Iterator[K]): Iterator[(K, V)]=
  {
    ks.flatMap { k => this(k).map(v => (k, v)) }
  }


  override def delete(ks: Iterator[Entry[V]]): SpatialRDDPartition[K, V]=
  {
    var newMap = this.tree

    newMap.removeAll(ks.toIterable)

    this.withMap(newMap)
  }


  /**
   * Updates the keys in `kvs` to their corresponding values generated by running `f` on old and new
   * values, if an old value exists, or `z` otherwise. Returns a new IndexedRDDPartition that
   * reflects the modification.
   */
  override def multiput[U](kvs: Iterator[(K, U)],
                           z: (K, U) => V,
                           f: (K, V, U) => V): SpatialRDDPartition[K, V] =
  {
    var newMap = this.tree

    for (ku <- kvs)
    {
      val oldpoint=Util.toPoint(ku._1)

      val oldV = newMap.searchPoint(oldpoint)

      val newV = if (oldV == null) z(ku._1, ku._2) else f(ku._1, oldV.value, ku._2)

      val newEntry=Util.toEntry(ku._1, newV)

       newMap.insert(oldpoint, newV)
    }

    this.withMap(newMap)
  }


  /**
   * this is used for range search
   * @param box
   * @param z
   * @tparam U
   * @return
   */
  def filter[U](box:U, z:Entry[V]=>Boolean):Iterator[(K, V)]=
  {
    val newMap = this.tree

    val ret=newMap.search(box.asInstanceOf[Box],z)
    ret.map(element=>(element.geom.asInstanceOf[K], element.value)).toIterator

  }

  /**
   * this is used for knn search over local data
   * @param entry
   * @param k
   * @param z
   * @tparam U
   * @return
   */
  def knnfilter[U](entry:U, k:Int, z:Entry[V]=>Boolean):Iterator[(K,V, Double)]=
  {
    entry match
    {
      case e:Point => this.tree.nearestKwithDistance(e,k,z).map
        {
          case(dist,element)=>
            (element.geom.asInstanceOf[K],element.value,dist)
        }.toIterator
    }
  }

  override def sjoin[U: ClassTag]
  (other: SpatialRDDPartition[K, U])
  (f: (K, V) => V): SpatialRDDPartition[K, V] = sjoin(other.iterator)(f)

  /**
   *the U need to be box
   */
  override def sjoin[U: ClassTag]
  (other: Iterator[(K, U)])
  (f: (K, V) => V): SpatialRDDPartition[K, V] = {

    val newMap = this.tree
    /**
     * below is used for hashmap based join
     */
    var retmap=new HashMap[K,V]

    def textfunction(entry:Entry[V]):Boolean={
      entry.value match
      {
        case s:String=>s.toLowerCase().contains("bitch")
      }
    }

    other.foreach{
      case(point,b:Box)=>
        val ret = newMap.search(b, _ => true)
        //val ret = newMap.search(b, textfunction)
        ret.foreach {
          case (e: Entry[V]) =>
            if(!retmap.contains(e.geom.asInstanceOf[K]))
              retmap = retmap + (e.geom.asInstanceOf[K] -> e.value)
        }
    }

    new SMapPartition(retmap)
  }

  /**
   * this is used for spatial join return format as u,iterator[(k,v)]
   */
  override def rjoin[U: ClassTag, U2:ClassTag]
  (other: SpatialRDDPartition[K, U])
  (f: (Iterator[(K,V)]) => U2,
   f2:(U2,U2)=>U2):  Iterator[(U, U2)] = rjoin(other.iterator)(f,f2)

  def rjoin[U: ClassTag, U2:ClassTag]
  (other: Iterator[(K, U)])
  (f: (Iterator[(K,V)]) => U2,
   f2:(U2,U2)=>U2): Iterator[(U, U2)]= {

    val buf = mutable.HashMap.empty[Geom,ArrayBuffer[(K,V)]]

    def updatehashmap(key:Geom, v2:V, k2:K)=
    {
      try {
        if(buf.contains(key))
        {
          val tmp1=buf.get(key).get
          tmp1.append(k2->v2)
          buf.put(key,tmp1)
        }else
        {
          val tmp1=new ArrayBuffer[(K,V)]
          tmp1.append((k2->v2))
          buf.put(key,tmp1)
        }

      }catch
        {
          case e:Exception=>
            println("out of memory for appending new value to the sjoin")
        }
    }

    val newMap = this.tree

    var retmap=new HashMap[K,V]

    other.foreach{
      case(point,b:Box)=>
        val ret = newMap.search(b, _ => true)
        //val ret = newMap.search(b, textfunction)
        ret.foreach {
          case (e: Entry[V]) =>
          {
            updatehashmap(b,e.value,e.geom.asInstanceOf[K])
          }
        }
    }

    buf.toIterator.map{
      case(g,array)=>
        val aggresult=f(array.toIterator)
        array.clear()
        (g.asInstanceOf[U], aggresult)
    }
  }

  override def knnjoin_[U: ClassTag]
  (other: SpatialRDDPartition[K, U],  knn:Int, f1:(K)=>Boolean,
   f2:(V)=>Boolean )
  : Iterator[(K, Double, Iterator[(K,V)])]=knnjoin_(other.iterator, knn, f1,f2)

  /** knn join operation
    * the other rdd is query rdd.
    * the key is the location of the query point, and value is k
    */
  override def knnjoin_[U: ClassTag]
  (other: Iterator[(K, U)],
   knn:Int,
   f1:(K)=>Boolean,
   f2:(V)=>Boolean ): Iterator[(K, Double, Iterator[(K,V)])]={

    val newMap = this.tree

    val ret=ArrayBuffer.empty[(K,Double,Iterator[(K,V)])]

    //nest loop knn search
    other.foreach{
      case(p:Point,k:Int)=>
        var max=0.0
        val tmp=newMap.nearestKwithDistance(p,k,id=>true).map{
          case(distance,entry)=>
            max=Math.max(max,distance)
            (entry.geom.asInstanceOf[K],entry.value)
        }.toIterator

        ret.append((p.asInstanceOf[K],max, tmp))
    }

    ret.toIterator
  }

  /**
   * @todo add this function for the quadtree next
   * @param other
   * @tparam U
   * @return
   */
  override def rkjoin(other: Iterator[(K, (K,Iterator[(K,V)]))],f1:(K)=>Boolean,
                      f2:(V)=>Boolean): Iterator[(K, Array[(K,V)])]=
  {

    other.map{
      case(locationpoint,(querypoint,itr))
        =>
        (querypoint,itr.toArray)
    }
  }

}
private[spatialrdd] object QtreePartition {

  def apply[K: ClassTag, V: ClassTag]
  (iter: Iterator[(K, V)]) =
    apply[K, V, V](iter, (id, a) => a, (id, a, b) => b)

  def apply[K: ClassTag, U: ClassTag, V: ClassTag]
  (iter: Iterator[(K, V)], z: (K, U) => V, f: (K, V, U) => V)
  : SpatialRDDPartition[K, V] =
  {
    val map = QTree(iter.map{ case(k, v) => Util.toEntry(k,v)})
    new QtreePartition(map)
  }

}