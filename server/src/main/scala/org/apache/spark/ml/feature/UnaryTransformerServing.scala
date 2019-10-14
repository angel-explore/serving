package org.apache.spark.ml.feature

import java.util

import org.apache.spark.ml.UnaryTransformer
import org.apache.spark.ml.data.{SDFrame, SRow, UDF}
import org.apache.spark.ml.linalg.{Vector, VectorUDT}
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.param.shared.{HasInputCol, HasOutputCol}
import org.apache.spark.ml.transformer.ServingTrans
import org.apache.spark.sql.types._

abstract class UnaryTransformerServing[IN, OUT, M <: UnaryTransformerServing[IN, OUT, M, T], T <: UnaryTransformer[IN, OUT, T]](stage: T)
  extends ServingTrans with HasInputCol with HasOutputCol{

  override def copy(extra: ParamMap): M = defaultCopy(extra)

  override def transformSchema(schema: StructType): StructType = {
    val inputType = schema(stage.getInputCol).dataType
    validateInputType(inputType)
    if (schema.fieldNames.contains(stage.getOutputCol)) {
      throw new IllegalArgumentException(s"Output column ${stage.getOutputCol} already exists.")
    }
    val outputFields = schema.fields :+
      StructField(stage.getOutputCol, outputDataType, nullable = false)
    StructType(outputFields)
  }

  /**
    * Validates the input type. Throw an exception if it is invalid.
    */
  protected def validateInputType(inputType: DataType): Unit = {}

  /**
    * Returns the data type of the output column.
    */
  def outputDataType: DataType

  override def prepareData(rows: Array[SRow]): SDFrame = {
    if (stage.isDefined(stage.inputCol)) {
      val schema = new StructType().add(new StructField(stage.getInputCol, new VectorUDT, true))
      new SDFrame(rows)(schema)
    } else {
      throw new Exception (s"inputCol or inputCols of ${stage} is not defined!")
    }
  }

  override def prepareData(feature: util.Map[String, _]): SDFrame = {
    if (stage.isDefined(stage.inputCol)) {
      val featureName = feature.keySet.toArray
      if (!featureName.contains(stage.getInputCol)) {
        throw new Exception (s"the ${stage.getInputCol} is not included in the input col(s)")
      } else if (!feature.get(stage.getInputCol).isInstanceOf[Vector]) {
        throw new Exception (s"the type of col ${stage.getInputCol} is not Vector")
      } else {
        val schema = new StructType().add(new StructField(stage.getInputCol, new VectorUDT, true))
        val rows =  Array(new SRow(Array(feature.get(stage.getInputCol))))
        new SDFrame(rows)(schema)
      }
    } else {
      throw new Exception (s"inputCol or inputCols of ${stage} is not defined!")
    }
  }

  override def valueType(): String = "double"
}
