package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import scala.collection.mutable.{Stack, HashSet, ArrayBuffer} 

private[passes] object Utils {
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

private[strober] object Analyses extends firrtl.passes.Pass {
  def name = "[strober] Analyze Circuit"
  
  def collectChildren(m: Module) {
    val children = collection.mutable.ArrayBuffer[String]()
    def visitInstances(s: Statement): Statement =
      s map visitInstances match {
        case inst: DefInstance =>
          children += inst.module
          inst
        case inst: WDefInstance =>
          children += inst.module
          inst
        case s => s
      }
    m.body map visitInstances
    StroberCompiler.context.children(m.name) = children.toVector
  }

  def run(c: Circuit) = {
    c.modules foreach {
      case m: ExtModule => // should be exception
      case m: Module => collectChildren(m)
    }
    c
  }
}
