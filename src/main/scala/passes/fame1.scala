package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap}

private[strober] object Fame1Transform extends firrtl.passes.Pass {
  def name = "[strober] Fame1 Transforms"
  import Utils._

  private val ut = UnknownType
  private val ug = UNKNOWNGENDER

  private def transform(m: DefModule): DefModule = m match {
    case m: ExtModule => m
    case m: Module =>
      val enables = HashSet[String]()
      val raddrs = HashSet[String]()
      val raddrIns = HashMap[String, Expression]()
      val stmts = ArrayBuffer[Statement]()

      def collectEnables(s: Statement): Statement = {
        s map collectEnables match {
          case mem: DefMemory =>
            raddrs ++= mem.readers map (r => s"${mem.name}.${r}.addr")
            enables ++= mem.readers map (r => s"${mem.name}.${r}.en")
            enables ++= mem.writers map (w => s"${mem.name}.${w}.en")
            enables ++= mem.readwriters map (rw => s"${mem.name}.${rw}.en")
            mem
          case s => s
        }
      }

      def collectConnects(s: Statement): Statement = {
        s map collectConnects match {
          case con: Connect if raddrs(con.loc.serialize) =>
            raddrIns(con.expr.serialize) = con.loc
            con
          case s => s
        }
      }

      val targetFirePort = Port(NoInfo, "targetFire", Input, UIntType(IntWidth(1)))
      def targetFire = Reference(targetFirePort.name, ut)
      def notTargetFire = DoPrim(PrimOps.Not, Seq(targetFire), Nil, ut)
      def connectTargetFire(s: Statement): Statement = {
        s map connectTargetFire match {
          case inst: WDefInstance =>
            val pin = WSubField(Reference(inst.name, ut), targetFire.name, ut, ug)
            Block(Seq(inst, Connect(NoInfo, pin, targetFire)))
          case reg: DefRegister =>
            def regRef = Reference(reg.name, ut)
            stmts += Conditionally(NoInfo, targetFire, EmptyStmt, Connect(NoInfo, regRef, regRef))
            reg
          case Connect(info, loc, exp) if enables(loc.serialize) =>
            val node = DefNode(NoInfo, s"""${loc.serialize replace (".", "_")}_fire""",
              DoPrim(PrimOps.And, Seq(exp, targetFire), Seq(), ut))
            Block(Seq(node, Connect(info, loc, Reference(node.name, ut))))
          case s => s
        }
      }

      Module(m.info, m.name, m.ports :+ targetFirePort, Block(
        (m.body map collectEnables map collectConnects map connectTargetFire) +: stmts.toSeq))
  }

  def run(c: Circuit) = {
    val transformedModules = (c.modules filter (x => wrappers(x.name)) flatMap { 
      case m: Module =>
        val targets = HashSet[String]()
        val connects = ArrayBuffer[Statement]()
        def connectTargetFire(s: Statement): Statement = {
          s map connectTargetFire match {
            case inst: WDefInstance if inst.name == "target" =>
              val pin = WSubField(Reference(inst.name, ut), "targetFire", ut, ug)
              targets += inst.module
              connects += Connect(NoInfo, pin, Reference("fire", ut))
              inst
            case s => s
          }
        }
        val body = Block((m.body map connectTargetFire) +: connects)
        val targetModules = c.modules filter (x => targets(x.name))
        (dfs(targetModules, c.modules)(transform) map (x => x.name -> x)) :+
        (m.name -> Module(m.info, m.name, m.ports, body))
      case m: ExtModule => Seq(m.name -> m) // should be execption
    }).toMap
    Circuit(c.info, c.modules map (m => transformedModules getOrElse (m.name, m)), c.main)
  }
}
