package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap}

private[strober] object Fame1Transform extends firrtl.passes.Pass {
  def name = "[strober] Fame1 Transforms"
  import Utils._

  private def transform(m: DefModule): DefModule = m match {
    case m: ExtModule => m
    case m: Module =>
      val targetFirePort = Port(NoInfo, "targetFire", Input, UIntType(IntWidth(1)))
      val daisyResetPort = Port(NoInfo, "daisyReset", Input, UIntType(IntWidth(1)))
      def targetFire = wref(targetFirePort.name)
      def daisyReset = wref(daisyResetPort.name)
      def notTargetFire = DoPrim(PrimOps.Not, Seq(targetFire), Nil, ut)
      def collectEnables(s: Statement): Seq[String] = s match {
        case s: DefMemory =>
          (s.readers map (r => s"${s.name}.${r}.en")) ++
          (s.writers map (w => s"${s.name}.${w}.en")) ++
          (s.readwriters map (rw => s"${s.name}.${rw}.en"))
        case s: Block => s.stmts flatMap collectEnables
        case _ => Nil
      }
      val enables = collectEnables(m.body).toSet
      val stmts = ArrayBuffer[Statement]()
      def connectTargetFire(s: Statement): Statement = {
        s map connectTargetFire match {
          case inst: WDefInstance =>
            Block(Seq(inst,
              Connect(NoInfo, wsub(wref(inst.name), "targetFire"), targetFire),
              Connect(NoInfo, wsub(wref(inst.name), "daisyReset"), daisyReset)
            ))
          case reg: DefRegister =>
            stmts += Conditionally(NoInfo, targetFire, EmptyStmt,
              Connect(NoInfo, wref(reg.name), wref(reg.name)))
            reg
          case Connect(info, loc, exp) if enables(loc.serialize) =>
            val node = DefNode(NoInfo, s"""${loc.serialize replace (".", "_")}_fire""",
              DoPrim(PrimOps.And, Seq(exp, targetFire), Seq(), ut))
            Block(Seq(node, Connect(info, loc, wref(node.name))))
          case s => s
        }
      }

      Module(m.info, m.name, m.ports ++ Seq(targetFirePort, daisyResetPort),
        Block((m.body map connectTargetFire) +: stmts.toSeq))
  }

  def run(c: Circuit) = {
    val transformedModules = (wrappers(c.modules) flatMap {
      case m: Module =>
        def connectTargetFire(s: Statement): Seq[Connect] = s match {
          case s: WDefInstance if s.name == "target" => Seq(
            Connect(NoInfo, wsub(wref("target"), "targetFire"), wref("fire")),
            Connect(NoInfo, wsub(wref("target"), "daisyReset"), wref("reset"))
          )
          case s: Block => s.stmts flatMap connectTargetFire
          case s => Nil
        }
        val body = Block(m.body +: connectTargetFire(m.body))
        (preorder(targets(m, c.modules), c.modules)(transform) map (
          x => x.name -> x)) :+ (m.name -> Module(m.info, m.name, m.ports, body))
      case m: ExtModule => Seq(m.name -> m)
    }).toMap
    Circuit(c.info, c.modules map (m => transformedModules getOrElse (m.name, m)), c.main)
  }
}
