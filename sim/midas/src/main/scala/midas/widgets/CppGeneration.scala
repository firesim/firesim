// See LICENSE for license details.

package midas
package widgets

import scala.language.implicitConversions

sealed trait CPPLiteral {
  def typeString: String
  def toC: String
}

sealed trait IntLikeLiteral extends CPPLiteral {
  def bitWidth: Int
  def literalSuffix: String
  def value: BigInt
  def toC = value.toString + literalSuffix

  require(bitWidth >= value.bitLength)
}

case class UInt32(value: BigInt) extends IntLikeLiteral {
  def typeString = "uint32_t"
  def bitWidth = 32
  def literalSuffix = ""
}

case class UInt64(value: BigInt) extends IntLikeLiteral {
  def typeString = "uint64_t"
  def bitWidth = 64
  def literalSuffix = "ULL"
}

case class Int64(value: BigInt) extends IntLikeLiteral {
  def typeString = "int64_t"
  def bitWidth = 64
  def literalSuffix = "LL"
}

case class CStrLit(val value: String) extends CPPLiteral {
  def typeString = "const char* const"
  def toC = "R\"ESC(%s)ESC\"".format(value)
}

object CppGenerationUtils {
  val indent = "  "

  def genEnum(name: String, values: Seq[String]): String =
    if (values.isEmpty) "" else s"enum $name {%s};\n".format(values mkString ",")

  def genArray[T <: CPPLiteral](name: String, values: Seq[T]): String = {
    val tpe = if (values.nonEmpty) values.head.typeString else "const void* const"
    val prefix = s"static $tpe $name [${math.max(values.size, 1)}] = {\n"
    val body = values map (indent + _.toC) mkString ",\n"
    val suffix = "\n};\n"
    prefix + body + suffix
  }

  def genStatic[T <: CPPLiteral](name: String, value: T): String =
    "static %s %s = %s;\n".format(value.typeString, name, value.toC)

  def genConstStatic[T <: CPPLiteral](name: String, value: T): String =
    "const static %s %s = %s;\n".format(value.typeString, name, value.toC)

  def genConst[T <: CPPLiteral](name: String, value: T): String =
    "const %s %s = %s;\n".format(value.typeString, name, value.toC)

  def genMacro(name: String, value: String = ""): String = s"#define $name $value\n"

  def genMacro[T <: CPPLiteral](name: String, value: T): String =
    "#define %s %s\n".format(name, value.toC)

  def genComment(str: String): String = "// %s\n".format(str)

  implicit def toStrLit(str: String): CStrLit = CStrLit(str)
}


