package strober
package passes

import firrtl._
import firrtl.Mappers._
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}

object Fame1Transform extends firrtl.passes.Pass {
  def name = "[strober] Fame1 Transforms"
  import Utils._

  private def transform(m: Module): Module = m match {
    case m: ExModule => m
    case m: InModule =>
      val enables = HashSet[String]()
      def collectEnables(s: Stmt): Stmt = {
        s map collectEnables match {
          case mem: DefMemory =>
            enables ++= mem.readers map (r => s"${mem.name}.${r}.en")
            enables ++= mem.writers map (w => s"${mem.name}.${w}.en")
            enables ++= mem.readwriters map (rw => s"${mem.name}.${rw}.en")
            mem
          case s => s
        }
      }

      val connectMap = HashMap[Expression, Expression]()
      def collectConnects(s: Stmt): Stmt = {
        s map collectConnects match {
          case con: Connect if enables(expToString(con.loc)) =>
            connectMap(con.loc) = con.exp
            con
          case s => s
        }
      }

      val targetFire = Port(NoInfo, "targetFire", INPUT, UIntType(IntWidth(1)))
      val whens = ArrayBuffer[Stmt]()
      def connectTargetFire(s: Stmt): Stmt = {
        s map connectTargetFire match {
          case inst: DefInstance =>
            Begin(Seq(
              inst,
              Connect(NoInfo, buildExp(inst.name, targetFire.name), buildExp(targetFire.name))
            ))
          case inst: WDefInstance =>
            Begin(Seq(
              inst,
              Connect(NoInfo, buildExp(inst.name, targetFire.name), buildExp(targetFire.name))
            ))
          case reg: DefRegister =>
            whens += Conditionally(reg.info,
              DoPrim(NOT_OP, Seq(buildExp(targetFire.name)), Seq(), UnknownType()),
              Connect(NoInfo, buildExp(reg.name), buildExp(reg.name)), Empty())
            reg
          case Connect(info, loc, exp) if connectMap contains loc =>
            val nodeName = s"""${expToString(loc) replace (".", "_")}_fire"""
            Begin(Seq(
              DefNode(NoInfo, nodeName,
                DoPrim(AND_OP, Seq(exp, buildExp(targetFire.name)), Seq(), UnknownType())
              ),
              Connect(NoInfo, loc, buildExp(nodeName))
            ))
          case s => s
        }
      }

      InModule(m.info, m.name, m.ports :+ targetFire, Begin(
        (m.body map collectEnables map collectConnects map connectTargetFire) +: whens))
  }

  def run(c: Circuit) = {
    val transformedModules = (c.modules filter (x => wrappers(x.name)) flatMap { 
      case m: InModule =>
        val targets = HashSet[String]()
        val connects = ArrayBuffer[Stmt]()
        def connectTargetFire(s: Stmt): Stmt = {
          s map connectTargetFire match {
            case inst: DefInstance if inst.name == "target" =>
              targets += inst.module
              connects += Connect(NoInfo, buildExp(inst.name, "targetFire"), buildExp("fire"))
              inst
            case inst: WDefInstance if inst.name == "target" =>
              targets += inst.module
              connects += Connect(NoInfo, buildExp(inst.name, "targetFire"), buildExp("fire"))
              inst
            case s => s
          }
        }
        val body = Begin((m.body map connectTargetFire) +: connects)
        val targetModules = c.modules filter (x => targets(x.name))
        (dfs(targetModules, c.modules)(transform) map (x => x.name -> x)) :+
        (m.name -> InModule(m.info, m.name, m.ports, body))
      case m: ExModule => Seq(m.name -> m) // should be execption
    }).toMap
    Circuit(c.info, c.modules map (m => transformedModules getOrElse (m.name, m)), c.main)
  }
}
