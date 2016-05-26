package strober
package passes

import firrtl._
import firrtl.Mappers._

object Analyses extends firrtl.passes.Pass {
  def name = "[strober] Analyze Circuit"
  
  def collectChildren(m: InModule) {
    val children = collection.mutable.ArrayBuffer[String]()
    def visitInstances(s: Stmt): Stmt =
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
      case m: ExModule => // should be exception
      case m: InModule => collectChildren(m)
    }
    c
  }
}
