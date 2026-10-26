package midas.passes

import firrtl._
import firrtl.ir._

package object xilinx {
  object DefineBUFGCE extends Transform with NoAnalysisPass {
    val blackboxName = "BUFGCE"
    val blackbox     = DefineAbstractClockGate.blackbox.copy(name = blackboxName, defname = blackboxName)
    val transformer = { c: Circuit => c.copy(modules = c.modules :+ blackbox) }
  }

  class ReplaceAbstractClockGates(clockGateName: String) extends Transform {
    def inputForm     = MidForm
    def outputForm    = MidForm
    override def name = "[Golden Gate] Xilinx ReplaceAbstractClockGates"

    def execute(state: CircuitState): CircuitState = {
      val clockModules = state.circuit.modules
        .collect({
          case m: ExtModule if m.defname == DefineAbstractClockGate.blackboxName => m.name
        })
        .toSet

      state.copy(circuit = state.circuit.mapModule(OrElseIdentity({
        case m: Module => {
          val onStmt: Function[Statement, Statement] = OrElseIdentity({
            case wi: WDefInstance if clockModules.contains(wi.module) =>
              wi.copy(module = clockGateName)
          })
          m.mapStmt { s => onStmt(s.mapStmt(onStmt)) }
        }
      })))
    }
  }

  object ReplaceAbstractClockGates extends ReplaceAbstractClockGates(DefineBUFGCE.blackboxName)

  object HostSpecialization extends SeqTransform {
    val inputForm  = LowForm
    val outputForm = LowForm
    val transforms = Seq(DefineBUFGCE, ReplaceAbstractClockGates)
  }

  class ConfiguredHostSpecialization(clockGateName: String) extends SeqTransform {
    require(clockGateName.matches("[A-Za-z_][A-Za-z0-9_]*"), "Invalid clock gate module name")
    val inputForm          = LowForm
    val outputForm         = LowForm
    private val definition = new Transform with NoAnalysisPass {
      val transformer = { c: Circuit =>
        c.copy(modules =
          c.modules :+
            DefineAbstractClockGate.blackbox.copy(name = clockGateName, defname = clockGateName)
        )
      }
    }
    val transforms         =
      if (clockGateName == DefineBUFGCE.blackboxName) HostSpecialization.transforms
      else Seq(definition, new ReplaceAbstractClockGates(clockGateName))
  }
}
