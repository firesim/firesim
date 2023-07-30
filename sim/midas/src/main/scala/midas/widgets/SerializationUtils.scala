package midas.widgets

import chisel3._

import scala.collection.immutable

object SerializationUtils {
  // Boxed types for different leaf chisel types we currently support
  case class SerializableType(typeString: String)
  val UIntType = SerializableType("UInt")
  val SIntType = SerializableType("SInt")

  case class SerializableField(name: String, tpe: SerializableType, fieldWidth: Int) {
    def regenType(): Data = tpe match {
      case UIntType => UInt(fieldWidth.W)
      case SIntType => SInt(fieldWidth.W)
      case _ => throw new Exception(s"Type string with no associated chisel type: ${tpe}")
    }
  }

  object SerializableField {
    def apply(name: String, field: Data): SerializableField = field match {
      case f: Aggregate =>  throw new Exception(s"Cannot serialize aggregate types; pass in leaf fields instead.")
      case f: UInt => SerializableField(name, UIntType, f.getWidth)
      case f: SInt => SerializableField(name, SIntType, f.getWidth)
      case _ => throw new Exception(s"Cannot serialize this field type")
    }
  }

  class RegeneratedTargetIO(inputs: Seq[SerializableField], outputs: Seq[SerializableField]) extends Record {
    val inputPorts  = inputs.map(field => field.name -> Input(field.regenType()))
    val outputPorts  = outputs.map(field => field.name -> Output(field.regenType()))
    override val elements = immutable.ListMap((inputPorts ++ outputPorts):_*)
  }
}

