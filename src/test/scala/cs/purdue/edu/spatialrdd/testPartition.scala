package cs.purdue.edu.spatialrdd

import cs.purdue.edu.spatialrdd.impl._
import cs.purdue.edu.spatialindex.rtree._

import scala.util.Random._

/**
 * Created by merlin on 9/20/15.
 */


object TestPartition {

  def main(args: Array[String]) {

    def uniformPoint(rangex:Int, rangey:Int):Point=
      Point(nextInt(rangex-2)+2, nextInt(rangey-2)+2)

    val points=(1 to 100).map(id=>uniformPoint(1000,1000))

    val elements=points.zipWithIndex.toIterator

    //val es = (1 to 100).map(id =>(uniformPoint(1000,1000), id)).toIterator

    val part1= RtreePartition(elements)

    //test for builid new index and get function
    /********************************************************/
    val results=part1.multiget(points.toIterator)
    assert(results.length==points.length)


    /**
     * test for multiput
     */
    var z = Array(Point(12,12), Point(17,18), Point(20,21))
    val maps=Map(Point(12,12)-> 1, Point(17,18)-> 12, Point(20,21)->100)
    maps.get(Point(12,12))

    val part4=part1.multiput[Int](maps.toIterator, (id, a) => a, (id, a, b) => b)
    part4.multiget(z.toIterator)//.foreach(println)
    //assert(part4.multiget(z.toIterator)===maps)

    /**
     * test for insert
     */
    /********************************************************/
    val points2=(1 to 100).map(id=>uniformPoint(100,100))

    val insertelement=points2.zipWithIndex.toIterator

    val part3=part1.multiput[Int](insertelement,(id, a) => a, (id, a, b) => b)

    val insertresult=part3.multiget(points2.toIterator)

    assert(insertresult.length==points2.length)
    /********************************************************/

    //test for delete
    val deleteelements=points2.map{
      point=>Util.toEntry(point,10)
    }
    val part2=part3.delete(deleteelements.toIterator)

    /********************************************************/
    //test for range search
    val box1 =  Box(0 , 0, 100, 100)
    val rangesearchresult=part3.filter(box1,(id)=>true)

    //println("range search result")
    //rangesearchresult.foreach{(ks)=>println(ks._1.toString+","+ks._2)}

  }
}