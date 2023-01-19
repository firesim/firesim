package midas.passes

import firrtl._
import firrtl.ir._

package object xilinx {
  object DefineBUFGCE extends Transform with NoAnalysisPass {
    val blackboxName = "BUFGCE"
    val blackbox = DefineAbstractClockGate.blackbox.copy(name = blackboxName, defname = blackboxName)
    val transformer = { c: Circuit => c.copy(modules = c.modules :+ blackbox) }
  }

  object ReplaceAbstractClockGates extends Transform {
    def inputForm = MidForm
    def outputForm = MidForm
    override def name = "[Golden Gate] Xilinx ReplaceAbstractClockGates"

    def execute(state: CircuitState): CircuitState = {
      val clockModules = state.circuit.modules.collect({
        case m : ExtModule if m.defname == DefineAbstractClockGate.blackboxName => m.name
      }).toSet


      state.copy(circuit = state.circuit.mapModule(OrElseIdentity({
        case m: Module => {
          val onStmt : Function[Statement, Statement] = OrElseIdentity({
            case wi: WDefInstance if clockModules.contains(wi.module) =>
              wi.copy(module = DefineBUFGCE.blackboxName)
          })
          m mapStmt { s => onStmt(s mapStmt onStmt) }
        }
      })))
    }
  }

  object HostSpecialization extends SeqTransform {
    val inputForm = LowForm
    val outputForm = LowForm
    val transforms = Seq(DefineBUFGCE, ReplaceAbstractClockGates)
  }
}
