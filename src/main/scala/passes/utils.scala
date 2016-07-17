package strober
package passes

import firrtl._
import firrtl.ir._
import scala.collection.mutable.{Stack, HashSet, ArrayBuffer} 

private[passes] object Utils {
  def expToString(e: Expression): String = e match {
    case Reference(name, _) => name
    case SubField(exp, name, _) => s"${expToString(exp)}.${name}"
    case SubIndex(exp, value, _) => s"${expToString(exp)}[$value]"
    case SubAccess(exp, index, _) => s"${expToString(exp)}[${expToString(index)}]"
    case WRef(name, _, _, _) => name
    case WSubField(exp, name, _, _) => s"${expToString(exp)}.${name}"
    case WSubIndex(exp, value, _, _) => s"${expToString(exp)}[$value]"
    case WSubAccess(exp, index, _, _) => s"${expToString(exp)}[${expToString(index)}]"
    case Mux(cond, tval, fval, _) => s"mux(${expToString(cond)}, ${expToString(tval)}, ${expToString(fval)})"
    case ValidIf(cond, value, _) => s"validif(${expToString(cond)}, ${expToString(value)})"
    case UIntLiteral(value, width) => s"""UInt<$width>("h${value.toString(16)}")"""
    case SIntLiteral(value, width) => s"""SInt<$width>("h${value.toString(16)}")"""
  } 
 
  // Takes a set of strings or ints and returns equivalent expression node
  //   Strings correspond to subfields/references, ints correspond to indexes
  // eg. Seq(io, port, ready)    => io.port.ready
  //     Seq(io, port, 5, valid) => io.port[5].valid
  //     Seq(3)                  => UInt("h3")
  def buildExp(names: Seq[Any]): Expression = {
    def rec(names: Seq[Any]): Expression = {
      names.head match {
        // Useful for adding on indexes or subfields
        case head: Expression => head
        // Int -> UInt/SInt/Index
        case head: Int =>
          if( names.tail.isEmpty ) // Is the UInt/SInt inference good enough?
            if( head > 0 ) UIntLiteral(head, UnknownWidth) else SIntLiteral(head, UnknownWidth)
          else WSubIndex(rec(names.tail), head, UnknownType, UNKNOWNGENDER)
        // String -> Reference/Subfield
        case head: String =>
          if( names.tail.isEmpty ) Reference(head, UnknownType)
          else WSubField(rec(names.tail), head, UnknownType, UNKNOWNGENDER)
        case _ => throw new Exception("Invalid argument type to buildExp! " + names)
      }
    }
    rec(names.reverse) // Let user specify in more natural format
  }
  def buildExp(head: Any, names: Any*): Expression = buildExp(head +: names.toSeq)

  def shims = StroberCompiler.context.shims
  def wrappers = StroberCompiler.context.wrappers
  def getChildren(parent: String) = StroberCompiler.context.children(parent)

  def dfs(heads: Seq[DefModule], modules: Seq[DefModule])(visit: DefModule => DefModule): Seq[DefModule] = {
    val stack = Stack[DefModule](heads:_*)
    val visited = HashSet[String]((heads map (_.name)):_*)
    val results = ArrayBuffer[DefModule]()
    while (!stack.isEmpty) {
      val top = stack.pop
      val children = getChildren(top.name).toSet
      results += visit(top)
      modules filter (x => children(x.name) && !visited(x.name)) foreach (stack push _)
      visited ++= children
    }
    results.toSeq
  }
}
