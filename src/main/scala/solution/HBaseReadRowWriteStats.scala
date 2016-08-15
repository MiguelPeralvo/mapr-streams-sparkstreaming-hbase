/*
 * This example reads a row of time series sensor data
 * calculates the the statistics for the hz data 
 * and then writes these statistics to the stats column family
 *  
 * you can specify specific columns to return, More info:
 * http://hbase.apache.org/apidocs/org/apache/hadoop/hbase/mapreduce/TableInputFormat.html
 */

package solution

import java.io.DataOutputStream
import scala.reflect.runtime.universe

import org.apache.hadoop.hbase.protobuf.ProtobufUtil
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.Put


import org.apache.hadoop.hbase.filter.PrefixFilter
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import  org.apache.hadoop.io.Writable

import org.apache.hadoop.hbase.mapred.TableOutputFormat

import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.mapred.JobConf
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.rdd.PairRDDFunctions
import org.apache.spark.sql.Row
import org.apache.spark.sql.functions.avg
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat

import org.apache.commons.io.output.ByteArrayOutputStream
import java.io.DataOutput
import org.apache.hadoop.fs.Path
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.Filter;



import org.apache.spark.rdd.RDD

import scala.collection.JavaConversions._

import org.apache.hadoop.hbase.{ Cell, CellUtil }
import org.apache.hadoop.hbase.client.{ Result, Scan }
import org.apache.hadoop.hbase.mapreduce.{ TableInputFormat, IdentityTableMapper, TableMapReduceUtil }
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.hbase.filter.Filter
import org.apache.hadoop.mapreduce.Job
import org.apache.spark._

import java.lang.String
import org.apache.hadoop.hbase.client.Scan
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.client.Result
import org.apache.hadoop.hbase.mapreduce.TableInputFormat
import org.apache.hadoop.hbase.util.Base64
import org.apache.hadoop.hbase.client._
import scala.collection.JavaConverters._

object HBaseReadRowWriteStats {

  case class SensorRow(rowkey: String, hz: Double, disp: Double, flo: Double, sedPPM: Double, psi: Double, chlPPM: Double)

  object SensorRow extends Serializable {
    def parseSensorRow(result: Result): SensorRow = {
      val rowkey = Bytes.toString(result.getRow())
      // remove time from rowKey, stats row key is for day
      val p0 = rowkey.split(" ")(0)
      val p1 = Bytes.toDouble(result.getValue(cfDataBytes, Bytes.toBytes("hz")))
      val p2 = Bytes.toDouble(result.getValue(cfDataBytes, Bytes.toBytes("disp")))
      val p3 = Bytes.toDouble(result.getValue(cfDataBytes, Bytes.toBytes("flo")))
      val p4 = Bytes.toDouble(result.getValue(cfDataBytes, Bytes.toBytes("sedPPM")))
      val p5 = Bytes.toDouble(result.getValue(cfDataBytes, Bytes.toBytes("psi")))
      val p6 = Bytes.toDouble(result.getValue(cfDataBytes, Bytes.toBytes("chlPPM")))
      SensorRow(p0, p1, p2, p3, p4, p5, p6)
    }
  }

  case class SensorStatsRow(
    rowkey: String,
    maxhz: Double, minhz: Double, avghz: Double,
    maxdisp: Double, mindisp: Double, avgdisp: Double,
    maxflo: Double, minflo: Double, avgflo: Double,
    maxsedPPM: Double, minsedPPM: Double, avgsedPPM: Double,
    maxpsi: Double, minpsi: Double, avgpsi: Double,
    maxchlPPM: Double, minchlPPM: Double, avgchlPPM: Double
  )

  object SensorStatsRow {
    def convertToPutStats(row: SensorStatsRow): (ImmutableBytesWritable, Put) = {
      val p = new Put(Bytes.toBytes(row.rowkey))
      // add columns with data values to put
      p.addColumn(cfStatsBytes, Bytes.toBytes("hzmax"), Bytes.toBytes(row.maxhz))
      p.addColumn(cfStatsBytes, Bytes.toBytes("hzmin"), Bytes.toBytes(row.minhz))
      p.addColumn(cfStatsBytes, Bytes.toBytes("hzavg"), Bytes.toBytes(row.avghz))
      p.addColumn(cfStatsBytes, Bytes.toBytes("dispmax"), Bytes.toBytes(row.maxdisp))
      p.addColumn(cfStatsBytes, Bytes.toBytes("dispmin"), Bytes.toBytes(row.mindisp))
      p.addColumn(cfStatsBytes, Bytes.toBytes("dispavg"), Bytes.toBytes(row.avgdisp))
      p.addColumn(cfStatsBytes, Bytes.toBytes("flomax"), Bytes.toBytes(row.maxflo))
      p.addColumn(cfStatsBytes, Bytes.toBytes("flomin"), Bytes.toBytes(row.minflo))
      p.addColumn(cfStatsBytes, Bytes.toBytes("floavg"), Bytes.toBytes(row.avgflo))
      p.addColumn(cfStatsBytes, Bytes.toBytes("sedPPMmax"), Bytes.toBytes(row.maxsedPPM))
      p.addColumn(cfStatsBytes, Bytes.toBytes("sedPPMmin"), Bytes.toBytes(row.minsedPPM))
      p.addColumn(cfStatsBytes, Bytes.toBytes("sedPPMavg"), Bytes.toBytes(row.avgsedPPM))
      p.addColumn(cfStatsBytes, Bytes.toBytes("psimax"), Bytes.toBytes(row.maxpsi))
      p.addColumn(cfStatsBytes, Bytes.toBytes("psimin"), Bytes.toBytes(row.minpsi))
      p.addColumn(cfStatsBytes, Bytes.toBytes("psiavg"), Bytes.toBytes(row.avgpsi))
      p.addColumn(cfStatsBytes, Bytes.toBytes("chlPPMmax"), Bytes.toBytes(row.maxchlPPM))
      p.addColumn(cfStatsBytes, Bytes.toBytes("chlPPMmin"), Bytes.toBytes(row.minchlPPM))
      p.addColumn(cfStatsBytes, Bytes.toBytes("chlPPMavg"), Bytes.toBytes(row.avgchlPPM))
      (new ImmutableBytesWritable, p)
    }
  }
  
 def convertScanToString(scan : Scan): String = { 
    val proto: org.apache.hadoop.hbase.protobuf.generated.ClientProtos.Scan  = ProtobufUtil.toScan(scan); 
     Base64.encodeBytes(proto.toByteArray()); 
  } 	
  
  final val tableName = "/user/user01/sensor"
  final val cfData = "data"
  final val cfDataBytes = Bytes.toBytes(cfData)
  final val cfStats = "stats"
  final val cfStatsBytes = Bytes.toBytes(cfStats)

  def main(args: Array[String]) {
    val sparkConf = new SparkConf().setAppName("HBaseTest")
    val sc = new SparkContext(sparkConf)
    val sqlContext = new org.apache.spark.sql.SQLContext(sc)
    import sqlContext.implicits._

    val conf = HBaseConfiguration.create()

    conf.set(TableInputFormat.INPUT_TABLE, tableName)
    // scan data column family
    conf.set(TableInputFormat.SCAN_COLUMNS, "data")

    val filter: Filter= new PrefixFilter(Bytes.toBytes("COHUTTA"));

    val scan: Scan = new Scan()
    scan.setFilter(filter);
 
    conf.set(TableInputFormat.SCAN, convertScanToString(scan))

    // Load an RDD of rowkey, result(ImmutableBytesWritable, Result) tuples from the table
    val hBaseRDD = sc.newAPIHadoopRDD(conf, classOf[TableInputFormat],
      classOf[org.apache.hadoop.hbase.io.ImmutableBytesWritable],
      classOf[org.apache.hadoop.hbase.client.Result])

    hBaseRDD.count()

    // transform (ImmutableBytesWritable, Result) tuples into an RDD of Results
    val resultRDD = hBaseRDD.map(tuple => tuple._2)
    resultRDD.count()
    // transform RDD of Results into an RDD of SensorRow objects 
    val sensorRDD = resultRDD.map(SensorRow.parseSensorRow)
    // change  RDD of SensorRow  objects to a DataFrame
    val sensorDF = sensorRDD.toDF()
    // Return the schema of this DataFrame
    sensorDF.printSchema()
    // Display the top 20 rows of DataFrame
    sensorDF.show()
    // group by the rowkey (sensorid_date) get average psi
    sensorDF.groupBy("rowkey").agg(avg(sensorDF("psi"))).take(5).foreach(println)
    // register the DataFrame as a temp table 
    sensorDF.registerTempTable("SensorRow")

    // group by the rowkey (sensorid_date) get average, max , min for all columns
    val sensorStatDF = sqlContext.sql("SELECT rowkey,MAX(hz) as maxhz, min(hz) as minhz, avg(hz) as avghz, MAX(disp) as maxdisp, min(disp) as mindisp, avg(disp) as avgdisp, MAX(flo) as maxflo, min(flo) as minflo, avg(flo) as avgflo,MAX(sedPPM) as maxsedPPM, min(sedPPM) as minsedPPM, avg(sedPPM) as avgsedPPM, MAX(psi) as maxpsi, min(psi) as minpsi, avg(psi) as avgpsi,MAX(chlPPM) as maxchlPPM, min(chlPPM) as minchlPPM, avg(chlPPM) as avgchlPPM FROM SensorRow GROUP BY rowkey")
    sensorStatDF.printSchema()
    sensorStatDF.take(5).foreach(println)

    // map the query result row to the SensorStatsRow object
    val sensorStatsRowRDD = sensorStatDF.map {
      case Row(rowkey: String,
        maxhz: Double, minhz: Double, avghz: Double, maxdisp: Double, mindisp: Double, avgdisp: Double,
        maxflo: Double, minflo: Double, avgflo: Double, maxsedPPM: Double, minsedPPM: Double, avgsedPPM: Double,
        maxpsi: Double, minpsi: Double, avgpsi: Double, maxchlPPM: Double, minchlPPM: Double, avgchlPPM: Double) =>
        SensorStatsRow(
          rowkey: String,
          maxhz: Double, minhz: Double, avghz: Double, maxdisp: Double, mindisp: Double, avgdisp: Double,
          maxflo: Double, minflo: Double, avgflo: Double, maxsedPPM: Double, minsedPPM: Double, avgsedPPM: Double,
          maxpsi: Double, minpsi: Double, avgpsi: Double, maxchlPPM: Double, minchlPPM: Double, avgchlPPM: Double
        )
    }

    sensorStatsRowRDD.take(5).foreach(println)

    // set JobConfiguration variables for writing to HBase
    val jobConfig: JobConf = new JobConf(conf, this.getClass)
    jobConfig.set("mapreduce.output.fileoutputformat.outputdir", "/user/user01/out")
    // set the HBase output table
    jobConfig.setOutputFormat(classOf[TableOutputFormat])
    jobConfig.set(TableOutputFormat.OUTPUT_TABLE, tableName)
    // convert the SensorStatsRow objects into HBase put objects and write to HBase
    sensorStatsRowRDD.map {
      case sensorStatsRow => SensorStatsRow.convertToPutStats(sensorStatsRow)
    }.saveAsHadoopDataset(jobConfig)
  }

}
