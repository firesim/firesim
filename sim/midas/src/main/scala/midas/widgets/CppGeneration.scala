// See LICENSE for license details.

package midas
package widgets

sealed trait CPPLiteral {
  def toC: String
}

case class CppBoolean(value: Boolean) extends CPPLiteral {
  def toC = if (value) "true" else "false"
}

case class CppStruct(name: String, fields: Seq[(String, CPPLiteral)]) extends CPPLiteral {
  def toC = s"${name}{${fields.map({ case (k, v) => s".${k} = ${v.toC}"}).mkString(",")}}"
}

case class StdMap(typeName: String, fields: Seq[(String, CPPLiteral)]) extends CPPLiteral {
  def toC = s"std::map<std::string, ${typeName}, std::less<>>{${fields.map({ case (k, v) => s"std::make_pair(${CStrLit(k).toC}, ${v.toC})"}).mkString(",")}}"
}

case class StdVector(typeName: String, elems: Seq[CPPLiteral]) extends CPPLiteral {
  def toC = s"std::vector<${typeName}>{${elems.map(_.toC).mkString(",\n")}}"
}

case class Verbatim(name: String) extends CPPLiteral {
  def toC = name
}

sealed trait IntLikeLiteral extends CPPLiteral {
  def bitWidth: Int
  def literalSuffix: String
  def value: BigInt
  def toC = value.toString + literalSuffix

  require(bitWidth >= value.bitLength)
}

case class UInt32(value: BigInt) extends IntLikeLiteral {
  def bitWidth = 32
  def literalSuffix = ""
}

case class UInt64(value: BigInt) extends IntLikeLiteral {
  def bitWidth = 64
  def literalSuffix = "ULL"
}

case class Int64(value: BigInt) extends IntLikeLiteral {
  def bitWidth = 64
  def literalSuffix = "LL"
}

case class CStrLit(val value: String) extends CPPLiteral {
  def typeString = "const char* const"
  def toC = "R\"ESC(%s)ESC\"".format(value)
}
