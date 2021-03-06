/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import java.io._
import java.math.{BigDecimal, BigInteger}
import java.nio.ByteBuffer

import scala.reflect.ClassTag

import org.apache.spark.Logging
import org.apache.spark.serializer._
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{MutableRow, SpecificMutableRow}
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

/**
 * The serialization stream for [[SparkSqlSerializer2]]. It assumes that the object passed in
 * its `writeObject` are [[Product2]]. The serialization functions for the key and value of the
 * [[Product2]] are constructed based on their schemata.
 * The benefit of this serialization stream is that compared with general-purpose serializers like
 * Kryo and Java serializer, it can significantly reduce the size of serialized and has a lower
 * allocation cost, which can benefit the shuffle operation. Right now, its main limitations are:
 *  1. It does not support complex types, i.e. Map, Array, and Struct.
 *  2. It assumes that the objects passed in are [[Product2]]. So, it cannot be used when
 *     [[org.apache.spark.util.collection.ExternalSorter]]'s merge sort operation is used because
 *     the objects passed in the serializer are not in the type of [[Product2]]. Also also see
 *     the comment of the `serializer` method in [[Exchange]] for more information on it.
 */
private[sql] class Serializer2SerializationStream(
    rowSchema: Array[DataType],
    out: OutputStream)
  extends SerializationStream with Logging {

  private val rowOut = new DataOutputStream(new BufferedOutputStream(out))
  private val writeRowFunc = SparkSqlSerializer2.createSerializationFunction(rowSchema, rowOut)

  override def writeObject[T: ClassTag](t: T): SerializationStream = {
    val kv = t.asInstanceOf[Product2[Row, Row]]
    writeKey(kv._1)
    writeValue(kv._2)

    this
  }

  override def writeKey[T: ClassTag](t: T): SerializationStream = {
    // No-op.
    this
  }

  override def writeValue[T: ClassTag](t: T): SerializationStream = {
    writeRowFunc(t.asInstanceOf[Row])
    this
  }

  def flush(): Unit = {
    rowOut.flush()
  }

  def close(): Unit = {
    rowOut.close()
  }
}

/**
 * The corresponding deserialization stream for [[Serializer2SerializationStream]].
 */
private[sql] class Serializer2DeserializationStream(
    rowSchema: Array[DataType],
    in: InputStream)
  extends DeserializationStream with Logging  {

  private val rowIn = new DataInputStream(new BufferedInputStream(in))

  private def rowGenerator(schema: Array[DataType]): () => (MutableRow) = {
    if (schema == null) {
      () => null
    } else {
      // It is safe to reuse the mutable row.
      val mutableRow = new SpecificMutableRow(schema)
      () => mutableRow
    }
  }

  // Functions used to return rows for key and value.
  private val getRow = rowGenerator(rowSchema)
  // Functions used to read a serialized row from the InputStream and deserialize it.
  private val readRowFunc = SparkSqlSerializer2.createDeserializationFunction(rowSchema, rowIn)

  override def readObject[T: ClassTag](): T = {
    readValue()
  }

  override def readKey[T: ClassTag](): T = {
    null.asInstanceOf[T] // intentionally left blank.
  }

  override def readValue[T: ClassTag](): T = {
    readRowFunc(getRow()).asInstanceOf[T]
  }

  override def close(): Unit = {
    rowIn.close()
  }
}

private[sql] class SparkSqlSerializer2Instance(
    rowSchema: Array[DataType])
  extends SerializerInstance {

  def serialize[T: ClassTag](t: T): ByteBuffer =
    throw new UnsupportedOperationException("Not supported.")

  def deserialize[T: ClassTag](bytes: ByteBuffer): T =
    throw new UnsupportedOperationException("Not supported.")

  def deserialize[T: ClassTag](bytes: ByteBuffer, loader: ClassLoader): T =
    throw new UnsupportedOperationException("Not supported.")

  def serializeStream(s: OutputStream): SerializationStream = {
    new Serializer2SerializationStream(rowSchema, s)
  }

  def deserializeStream(s: InputStream): DeserializationStream = {
    new Serializer2DeserializationStream(rowSchema, s)
  }
}

/**
 * SparkSqlSerializer2 is a special serializer that creates serialization function and
 * deserialization function based on the schema of data. It assumes that values passed in
 * are Rows.
 */
private[sql] class SparkSqlSerializer2(rowSchema: Array[DataType])
  extends Serializer
  with Logging
  with Serializable{

  def newInstance(): SerializerInstance = new SparkSqlSerializer2Instance(rowSchema)

  override def supportsRelocationOfSerializedObjects: Boolean = {
    // SparkSqlSerializer2 is stateless and writes no stream headers
    true
  }
}

private[sql] object SparkSqlSerializer2 {

  final val NULL = 0
  final val NOT_NULL = 1

  /**
   * Check if rows with the given schema can be serialized with ShuffleSerializer.
   * Right now, we do not support a schema having complex types or UDTs, or all data types
   * of fields are NullTypes.
   */
  def support(schema: Array[DataType]): Boolean = {
    if (schema == null) return true

    var allNullTypes = true
    var i = 0
    while (i < schema.length) {
      schema(i) match {
        case NullType => // Do nothing
        case udt: UserDefinedType[_] =>
          allNullTypes = false
          return false
        case array: ArrayType =>
          allNullTypes = false
          return false
        case map: MapType =>
          allNullTypes = false
          return false
        case struct: StructType =>
          allNullTypes = false
          return false
        case _ =>
          allNullTypes = false
      }
      i += 1
    }

    // If types of fields are all NullTypes, we return false.
    // Otherwise, we return true.
    return !allNullTypes
  }

  /**
   * The util function to create the serialization function based on the given schema.
   */
  def createSerializationFunction(schema: Array[DataType], out: DataOutputStream): Row => Unit = {
    (row: Row) =>
      // If the schema is null, the returned function does nothing when it get called.
      if (schema != null) {
        var i = 0
        while (i < schema.length) {
          schema(i) match {
            // When we write values to the underlying stream, we also first write the null byte
            // first. Then, if the value is not null, we write the contents out.

            case NullType => // Write nothing.

            case BooleanType =>
              if (row.isNullAt(i)) {
                out.writeByte(NULL)
              } else {
                out.writeByte(NOT_NULL)
                out.writeBoolean(row.getBoolean(i))
              }

            case ByteType =>
              if (row.isNullAt(i)) {
                out.writeByte(NULL)
              } else {
                out.writeByte(NOT_NULL)
                out.writeByte(row.getByte(i))
              }

            case ShortType =>
              if (row.isNullAt(i)) {
                out.writeByte(NULL)
              } else {
                out.writeByte(NOT_NULL)
                out.writeShort(row.getShort(i))
              }

            case IntegerType | DateType =>
              if (row.isNullAt(i)) {
                out.writeByte(NULL)
              } else {
                out.writeByte(NOT_NULL)
                out.writeInt(row.getInt(i))
              }

            case LongType | TimestampType =>
              if (row.isNullAt(i)) {
                out.writeByte(NULL)
              } else {
                out.writeByte(NOT_NULL)
                out.writeLong(row.getLong(i))
              }

            case FloatType =>
              if (row.isNullAt(i)) {
                out.writeByte(NULL)
              } else {
                out.writeByte(NOT_NULL)
                out.writeFloat(row.getFloat(i))
              }

            case DoubleType =>
              if (row.isNullAt(i)) {
                out.writeByte(NULL)
              } else {
                out.writeByte(NOT_NULL)
                out.writeDouble(row.getDouble(i))
              }

            case StringType =>
              if (row.isNullAt(i)) {
                out.writeByte(NULL)
              } else {
                out.writeByte(NOT_NULL)
                val bytes = row.getAs[UTF8String](i).getBytes
                out.writeInt(bytes.length)
                out.write(bytes)
              }

            case BinaryType =>
              if (row.isNullAt(i)) {
                out.writeByte(NULL)
              } else {
                out.writeByte(NOT_NULL)
                val bytes = row.getAs[Array[Byte]](i)
                out.writeInt(bytes.length)
                out.write(bytes)
              }

            case decimal: DecimalType =>
              if (row.isNullAt(i)) {
                out.writeByte(NULL)
              } else {
                out.writeByte(NOT_NULL)
                val value = row.apply(i).asInstanceOf[Decimal]
                val javaBigDecimal = value.toJavaBigDecimal
                // First, write out the unscaled value.
                val bytes: Array[Byte] = javaBigDecimal.unscaledValue().toByteArray
                out.writeInt(bytes.length)
                out.write(bytes)
                // Then, write out the scale.
                out.writeInt(javaBigDecimal.scale())
              }
          }
          i += 1
        }
      }
  }

  /**
   * The util function to create the deserialization function based on the given schema.
   */
  def createDeserializationFunction(
      schema: Array[DataType],
      in: DataInputStream): (MutableRow) => InternalRow = {
    if (schema == null) {
      (mutableRow: MutableRow) => null
    } else {
      (mutableRow: MutableRow) => {
        var i = 0
        while (i < schema.length) {
          schema(i) match {
            // When we read values from the underlying stream, we also first read the null byte
            // first. Then, if the value is not null, we update the field of the mutable row.

            case NullType => mutableRow.setNullAt(i) // Read nothing.

            case BooleanType =>
              if (in.readByte() == NULL) {
                mutableRow.setNullAt(i)
              } else {
                mutableRow.setBoolean(i, in.readBoolean())
              }

            case ByteType =>
              if (in.readByte() == NULL) {
                mutableRow.setNullAt(i)
              } else {
                mutableRow.setByte(i, in.readByte())
              }

            case ShortType =>
              if (in.readByte() == NULL) {
                mutableRow.setNullAt(i)
              } else {
                mutableRow.setShort(i, in.readShort())
              }

            case IntegerType | DateType =>
              if (in.readByte() == NULL) {
                mutableRow.setNullAt(i)
              } else {
                mutableRow.setInt(i, in.readInt())
              }

            case LongType | TimestampType =>
              if (in.readByte() == NULL) {
                mutableRow.setNullAt(i)
              } else {
                mutableRow.setLong(i, in.readLong())
              }

            case FloatType =>
              if (in.readByte() == NULL) {
                mutableRow.setNullAt(i)
              } else {
                mutableRow.setFloat(i, in.readFloat())
              }

            case DoubleType =>
              if (in.readByte() == NULL) {
                mutableRow.setNullAt(i)
              } else {
                mutableRow.setDouble(i, in.readDouble())
              }

            case StringType =>
              if (in.readByte() == NULL) {
                mutableRow.setNullAt(i)
              } else {
                val length = in.readInt()
                val bytes = new Array[Byte](length)
                in.readFully(bytes)
                mutableRow.update(i, UTF8String.fromBytes(bytes))
              }

            case BinaryType =>
              if (in.readByte() == NULL) {
                mutableRow.setNullAt(i)
              } else {
                val length = in.readInt()
                val bytes = new Array[Byte](length)
                in.readFully(bytes)
                mutableRow.update(i, bytes)
              }

            case decimal: DecimalType =>
              if (in.readByte() == NULL) {
                mutableRow.setNullAt(i)
              } else {
                // First, read in the unscaled value.
                val length = in.readInt()
                val bytes = new Array[Byte](length)
                in.readFully(bytes)
                val unscaledVal = new BigInteger(bytes)
                // Then, read the scale.
                val scale = in.readInt()
                // Finally, create the Decimal object and set it in the row.
                mutableRow.update(i, Decimal(new BigDecimal(unscaledVal, scale)))
              }
          }
          i += 1
        }

        mutableRow
      }
    }
  }
}
