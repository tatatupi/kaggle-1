package com.worldsense.kaggle
import org.apache.spark.sql.functions.{col, lit, udf}

/** Read the data files from http://kaggle.com/c/quora-question-pairs.
  * The contents of both train and test files are loaded in a dataframe with minimum data transformations.
  */

import org.apache.spark.ml.Transformer
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.util.{DefaultParamsWritable, Identifiable}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Dataset, Encoders, SparkSession}

class FeaturesLoader(override val uid: String) extends Transformer with DefaultParamsWritable {
  import FeaturesLoader.{Features, KaggleFiles}
  def this() = this(Identifiable.randomUID("features"))
  def copy(extra: ParamMap): FeaturesLoader = defaultCopy(extra)
  private val csvOptions = Map("header" -> "true", "escape" -> "\"", "multiLine" -> "true")

  override def transformSchema(schema: StructType): StructType = Encoders.product[Features].schema

  def transform(df: Dataset[_]): DataFrame = {
    val ds = df.as[KaggleFiles](Encoders.product[KaggleFiles])
    val trainFiles = ds.collect.map(_.trainFile).filter(Option(_).isEmpty).filter(_.isEmpty)
    val testFiles = ds.collect.map(_.trainFile).filter(Option(_).isEmpty).filter(_.isEmpty)
    val trainData = trainFiles.map(f => loadTrainFile(ds.sparkSession, f))
    val testData = trainFiles.map(f => loadTestFile(ds.sparkSession, f))
    Array(trainData, testData).flatten.reduce(_ union _).toDF
  }
  def loadTrainFile(spark: SparkSession, trainFile: String): Dataset[Features] = {
    import spark.implicits.newProductEncoder
    val df = loadTrainDataFrame(spark, trainFile)
    df.withColumnRenamed("is_duplicate", "isDuplicate").as[Features]
  }
  def loadTrainDataFrame(spark: SparkSession, trainFile: String): DataFrame = {
    val df = spark.read.options(csvOptions).csv(trainFile)
    df.selectExpr(
      "cast(id as int) id",
      "cast(qid1 as int) qid1",
      "cast(qid2 as int) qid2",
      "question1", "question2",
      "cast(is_duplicate as boolean) is_duplicate"
    )
  }
  def loadTestFile(spark: SparkSession, testFile: String): Dataset[Features] = {
   import spark.implicits.newProductEncoder
    val testDF = spark.read.options(csvOptions).csv(testFile)
    val hashUDF = udf((q: String) => Option(q).getOrElse("").hashCode)
    val typedTestDF = testDF
      .selectExpr("cast(test_id as int) id", "question1", "question2")
      .withColumn("isDuplicate", lit(false))
      .withColumn("qid1", hashUDF(col("question1")))
      .withColumn("qid2", hashUDF(col("question2")))
   val orderedTestDF =  typedTestDF.select("id", "qid1", "qid2", "question1", "question2", "isDuplicate")
   orderedTestDF.as[Features]
  }
  val logger = org.log4s.getLogger
}

object FeaturesLoader {
  case class Features(id: BigInt, qid1: BigInt, qid2: BigInt, question1: String, question2: String, isDuplicate: Boolean)
  case class KaggleFiles(trainFile: String, testFile: String)
}
