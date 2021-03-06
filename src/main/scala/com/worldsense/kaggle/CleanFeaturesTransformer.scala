package com.worldsense.kaggle

import com.ibm.icu.text.{Normalizer2, Transliterator}
import com.worldsense.kaggle.FeaturesLoader.Features
import org.apache.spark.ml.Transformer
import org.apache.spark.ml.param.{Param, ParamMap}
import org.apache.spark.ml.util.{DefaultParamsWritable, Identifiable}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Dataset, Encoders}

class CleanFeaturesTransformer(override val uid: String) extends Transformer with DefaultParamsWritable {
  final val removeDiacriticalsParam: Param[Boolean] = new Param(this, "tokenizer", "estimator for selection")

  def this() = this(Identifiable.randomUID("cleanfeatures"))

  def copy(extra: ParamMap): CleanFeaturesTransformer = defaultCopy(extra)
  setDefault(removeDiacriticalsParam, true)

  def setRemoveDiacriticals(value: Boolean): this.type = set(removeDiacriticalsParam, value)

  override def transformSchema(schema: StructType): StructType = Encoders.product[Features].schema
  def transform(ds: Dataset[_]): DataFrame = {
    CleanFeaturesTransformer.cleanDataset(ds, $(removeDiacriticalsParam)).toDF
  }
}

object CleanFeaturesTransformer {
  private val normalizer = Normalizer2.getNFKCCasefoldInstance
  // Remove diacriticals, straight from http://userguide.icu-project.org/transforms/general
  private val unaccenter = Transliterator.getInstance("NFD; [:Nonspacing Mark:] Remove; NFC")
  private[kaggle] def normalize(text: String, removeDiacriticals: Boolean = true): String = {
    if (removeDiacriticals) normalizer.normalize(unaccenter.transliterate(text))
    else normalizer.normalize(text)
  }
  private[kaggle] def cleanDataset(ds: Dataset[_], removeDiacriticals: Boolean): Dataset[Features] = {
    import ds.sparkSession.implicits.newProductEncoder
    val features = ds.as[Features]
    // We have rows missing the id in the test data, drop then.
    val valid = features.na.drop(Seq("id"))
    // We replace null cells with the most straightforward default values.
    val sane = valid.na.fill(0).na.fill("").as[Features]
    val clean = sane.map { row =>
      row.copy(
        question1 = normalize(row.question1, removeDiacriticals),
        question2 = normalize(row.question2, removeDiacriticals)
      )
    }
    clean
  }
}