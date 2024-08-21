// See LICENSE for license details.

package firesim.lib.bridgeutils

import scala.collection.immutable

import chisel3.{fromIntToWidth, Aggregate, Data, Input, Output, Record, SInt, UInt}

object SerializationUtils {
  // Boxed types for different leaf chisel types we currently support
  case class SerializableType(typeString: String)
  val UIntType = SerializableType("UInt")
  val SIntType = SerializableType("SInt")

  case class SerializableField(name: String, tpe: SerializableType, fieldWidth: Int) {
    def regenType(): Data = tpe match {
      case UIntType => UInt(fieldWidth.W)
      case SIntType => SInt(fieldWidth.W)
      case _        => throw new Exception(s"Type string with no associated chisel type: ${tpe}")
    }
  }

  object SerializableField {
    def apply(name: String, field: Data): SerializableField = field match {
      case _: Aggregate => throw new Exception("Cannot serialize aggregate types; pass in leaf fields instead.")
      case f: UInt      => SerializableField(name, UIntType, f.getWidth)
      case f: SInt      => SerializableField(name, SIntType, f.getWidth)
      case _            => throw new Exception("Cannot serialize this field type")
    }
  }

  class RegeneratedTargetIO(inputs: Seq[SerializableField], outputs: Seq[SerializableField]) extends Record {
    val inputPorts        = inputs.map(field => field.name -> Input(field.regenType()))
    val outputPorts       = outputs.map(field => field.name -> Output(field.regenType()))
    override val elements = immutable.ListMap((inputPorts ++ outputPorts): _*)
  }
}
