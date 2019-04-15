package org.apache.spark.ml.feature

import org.apache.spark.ml.attribute.AttributeGroup
import org.apache.spark.ml.data.{SCol, SDFrame, UDF}
import org.apache.spark.ml.linalg.{Vector, VectorUDT, Vectors}
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.transformer.ServingTrans
import org.apache.spark.ml.util.{SchemaUtils}
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils
import org.apache.spark.util.collection.OpenHashMap

class FeatureHasherServing(stage: FeatureHasher) extends ServingTrans {
  
  override def transform(dataset: SDFrame): SDFrame = {
    val hashFunc: Any => Int = FeatureHasher.murmur3Hash
    val n = stage.getNumFeatures
    val localInputCols = stage.getInputCols
    val catCols = if (stage.isSet(stage.categoricalCols)) {
      stage.getCategoricalCols.toSet
    } else {
      Set[String]()
    }

    val outputSchema = transformSchema(dataset.schema)
    val realFields = outputSchema.fields.filter { f =>
      f.dataType.isInstanceOf[NumericType] && !catCols.contains(f.name)
    }.map(_.name).toSet

    def getDouble(x: Any): Double = {
      x match {
        case n: java.lang.Number =>
          n.doubleValue()
        case other =>
          // will throw ClassCastException if it cannot be cast, as would row.getDouble
          other.asInstanceOf[Double]
      }
    }

    val hashFeaturesUDF = UDF.make[Vector, Seq[Any]](row => {
      val map = new OpenHashMap[Int, Double]()
      localInputCols.foreach { colName =>
        val fieldIndex = outputSchema.fieldIndex(colName)
        if (row.length > fieldIndex) {
          val (rawIdx, value) = if (realFields(colName)) {
            // numeric values are kept as is, with vector index based on hash of "column_name"
            val value = getDouble(row(fieldIndex))
            val hash = hashFunc(colName)
            (hash, value)
          } else {
            // string, boolean and numeric values that are in catCols are treated as categorical,
            // with an indicator value of 1.0 and vector index based on hash of "column_name=value"
            val value = row(fieldIndex)
            val fieldName = s"$colName=$value"
            val hash = hashFunc(fieldName)
            (hash, 1.0)
          }
          val idx = Utils.nonNegativeMod(rawIdx, n)
          map.changeValue(idx, value, v => v + value)
        }
      }
      Vectors.sparse(n, map.toSeq)
    }, true)


    val metadata = outputSchema(stage.getOutputCol).metadata
    val inputFeatures = stage.getInputCols.map(c => dataset.schema(c))
    val featureCols = inputFeatures.map { f =>
      f.dataType match {
        case DoubleType => dataset(f.name)
        case BooleanType => dataset(f.name)
        case StringType => dataset(f.name)
        case _: VectorUDT => dataset(f.name)
        case _: NumericType | BooleanType => dataset(f.name)
      }
    }
    println(featureCols.length)
    dataset.select(SCol(),
      hashFeaturesUDF.apply(stage.getOutputCol, featureCols:_*).setSchema(stage.getOutputCol, metadata))
  }

  override def copy(extra: ParamMap): ServingTrans = {
    new FeatureHasherServing(stage.copy(extra))
  }

  override def transformSchema(schema: StructType): StructType = {
    val fields = schema(stage.getInputCols.toSet)
    fields.foreach { fieldSchema =>
      val dataType = fieldSchema.dataType
      val fieldName = fieldSchema.name
      require(dataType.isInstanceOf[NumericType] ||
        dataType.isInstanceOf[StringType] ||
        dataType.isInstanceOf[BooleanType],
        s"FeatureHasher requires columns to be of NumericType, BooleanType or StringType. " +
          s"Column $fieldName was $dataType")
    }
    val attrGroup = new AttributeGroup(stage.getOutputCol, stage.getNumFeatures)
    SchemaUtils.appendColumn(schema, attrGroup.toStructField())
  }

  override val uid: String = stage.uid
}

object FeatureHasherServing {
  def apply(stage: FeatureHasher): FeatureHasherServing = new FeatureHasherServing(stage)
}