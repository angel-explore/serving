package org.apache.spark.ml.feature

import java.util

import org.apache.spark.SparkException
import org.apache.spark.ml.attribute.{Attribute, AttributeGroup, NumericAttribute, UnresolvedAttribute}
import org.apache.spark.ml.data.{SCol, SDFrame, SRow, UDF}
import org.apache.spark.ml.linalg._
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.transformer.ServingTrans
import org.apache.spark.sql.types._

import scala.collection.mutable.ArrayBuilder

class VectorAssemblerServing(stage: VectorAssembler) extends ServingTrans{
  private var value_Type: String = ""

  override def transform(dataset: SDFrame): SDFrame = {
    transformSchema(dataset.schema, logging = true)
    // Schema transformation.
    val schema = dataset.schema
    lazy val first = dataset.getRow(0)
    val attrs = stage.getInputCols.flatMap { c =>
      val field = schema(c)
      val index = schema.fieldIndex(c)
      field.dataType match {
        case DoubleType =>
          val attr = Attribute.fromStructField(field)
          // If the input column doesn't have ML attribute, assume numeric.
          if (attr == UnresolvedAttribute) {
            Some(NumericAttribute.defaultAttr.withName(c))
          } else {
            Some(attr.withName(c))
          }
        case _: NumericType | BooleanType =>
          // If the input column type is a compatible scalar type, assume numeric.
          Some(NumericAttribute.defaultAttr.withName(c))
        case _: VectorUDT =>
          val group = AttributeGroup.fromStructField(field)
          if (group.attributes.isDefined) {
            // If attributes are defined, copy them with updated names.
            group.attributes.get.zipWithIndex.map { case (attr, i) =>
              if (attr.name.isDefined) {
                // TODO: Define a rigorous naming scheme.
                attr.withName(c + "_" + attr.name.get)
              } else {
                attr.withName(c + "_" + i)
              }
            }
          } else {
            // Otherwise, treat all attributes as numeric. If we cannot get the number of attributes
            // from metadata, check the first row.
            val numAttrs = group.numAttributes.getOrElse(first.get(index).asInstanceOf[Vector].size)
            Array.tabulate(numAttrs)(i => NumericAttribute.defaultAttr.withName(c + "_" + i))
          }
        case otherType =>
          throw new SparkException(s"VectorAssembler does not support the $otherType type")
      }
    }
    val metadata = new AttributeGroup(stage.getOutputCol, attrs).toMetadata()


    // Data transformation.
    val assembleUDF = UDF.make[Vector, Seq[Any]](VectorAssemblerServing.assemble, true)
    val args = stage.getInputCols.map { c =>
      schema(c).dataType match {
        case DoubleType => dataset(c)
        case _: VectorUDT => dataset(c)
        case _: NumericType | BooleanType => dataset(c)//todo: whether name is needed .as(s"${c}_double_$uid")
      }
    }

    dataset.select(SCol(), assembleUDF(stage.getOutputCol, args:_*).setSchema(stage.getOutputCol, metadata))
  }

  override def copy(extra: ParamMap): VectorAssemblerServing = {
    new VectorAssemblerServing(stage.copy(extra))
  }

  override def transformSchema(schema: StructType): StructType = {
    val inputColNames = stage.getInputCols
    val outputColName = stage.getOutputCol
    val incorrectColumns = inputColNames.flatMap { name =>
      schema(name).dataType match {
        case _: NumericType | BooleanType => None
        case t if t.isInstanceOf[VectorUDT] => None
        case other => Some(s"Data type $other of column $name is not supported.")
      }
    }
    if (incorrectColumns.nonEmpty) {
      throw new IllegalArgumentException(incorrectColumns.mkString("\n"))
    }
    if (schema.fieldNames.contains(outputColName)) {
      throw new IllegalArgumentException(s"Output column $outputColName already exists.")
    }
    StructType(schema.fields :+ StructField(outputColName, new VectorUDT, true))
  }

  override val uid: String = stage.uid

  override def prepareData(rows: Array[SRow]): SDFrame = {
    if(stage.isDefined(stage.inputCols)) {
      val featuresTypes = rows(0).values.map{ feature =>
        feature match {
          case _ : Double => {
            setValueType("double")
            DoubleType
          }
          case _ : String =>
            setValueType("string")
            StringType
          case _ : Integer =>
            setValueType("int")
            IntegerType
          case _ : Vector =>
            setValueType("double")
            new VectorUDT
          case _ : Array[String] =>
            setValueType("string")
            ArrayType(StringType)
        }
      }
      var schema: StructType = null
      val iter = stage.getInputCols.zip(featuresTypes).iterator
      while (iter.hasNext) {
        val (colName, featureType) = iter.next()
        if (schema == null) {
          schema = new StructType().add(new StructField(colName, featureType, true))
        } else {
          schema = schema.add(new StructField(colName, featureType, true))
        }
      }

      new SDFrame(rows)(schema)
    } else {
      throw new Exception (s"inputCol or inputCols of ${stage} is not defined!")
    }
  }

  override def prepareData(feature: util.Map[String, _]): SDFrame = {
    var schema: StructType = null
    val rows = new Array[Any](feature.size())
    if (stage.isDefined(stage.inputCols)) {
      val featureNames = feature.keySet.toArray
      if (featureNames.size < stage.getInputCols.length) {
        throw new Exception (s"the input cols doesn't match ${stage.getInputCols.length}")
      } else {
        var index = 0
        stage.getInputCols.foreach{ colName =>
          if (!feature.containsKey(colName)) {
            throw new Exception (s"the ${colName} is not included in the input col(s)")
          } else {
            val value = feature.get(colName)
            val valueType = value match {
              case _ : Double => {
                setValueType("double")
                DoubleType
              }
              case _ : String =>
                setValueType("string")
                StringType
              case _ : Integer =>
                setValueType("int")
                IntegerType
              case _ : Vector =>
                setValueType("double")
                new VectorUDT
              case _ : Array[String] =>
                setValueType("string")
                ArrayType(StringType)
            }
            if (schema == null) {
              schema = new StructType().add(new StructField(colName, valueType, true))
            } else {
              schema = schema.add(new StructField(colName, valueType, true))
            }
            rows(index) = value
            index += 1
          }
        }
        new SDFrame(Array(new SRow(rows)))(schema)
      }
    } else {
      throw new Exception (s"inputCol or inputCols of ${stage} is not defined!")
    }
  }

  override def valueType(): String = value_Type

  def setValueType(vtype: String): Unit ={
    value_Type = vtype
  }
}

object VectorAssemblerServing {
  def apply(stage: VectorAssembler): VectorAssemblerServing = new VectorAssemblerServing(stage)

  private def assemble(vv: Any*): Vector = {
    val indices = ArrayBuilder.make[Int]
    val values = ArrayBuilder.make[Double]
    var cur = 0
    vv.foreach {
      case v: Int =>
        if (v != 0.0) {
          indices += cur
          values += v
        }
        cur += 1
      case v: Long =>
        if (v != 0.0) {
          indices += cur
          values += v
        }
        cur += 1
      case v: Float =>
        if (v != 0.0) {
          indices += cur
          values += v
        }
        cur += 1
      case v: Double =>
        if (v != 0.0) {
          indices += cur
          values += v
        }
        cur += 1
      case vec: Vector =>
        vec.foreachActive { case (i, v) =>
          if (v != 0.0) {
            indices += cur + i
            values += v
          }
        }
        cur += vec.size
      case null =>
        // TODO: output Double.NaN?
        throw new SparkException("Values to assemble cannot be null.")
      case o =>
        throw new SparkException(s"$o of type ${o.getClass.getName} is not supported.")
    }
    Vectors.sparse(cur, indices.result(), values.result()).compressed
  }
}