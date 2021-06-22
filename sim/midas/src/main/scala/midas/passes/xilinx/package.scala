package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.annotations.{CircuitTarget, CompleteTarget}
import firrtl.passes.Pass
import firrtl.Utils.BoolType

package object xilinx {
  object DefineBUFGCE extends Transform with NoAnalysisPass {
    val blackboxName = "BUFGCE"
    val blackbox = DefineAbstractClockGate.blackbox.copy(name = blackboxName, defname = blackboxName)
    val transformer = { c: Circuit => c.copy(modules = c.modules :+ blackbox) }
  }

  object ReplaceAbstractClockGates extends Transform with NoAnalysisPass {
    val transformer = StatementTransformer {
      case wi: WDefInstance if wi.module == DefineAbstractClockGate.blackboxName =>
        wi.copy(module = DefineBUFGCE.blackboxName)
    }
  }

  object HostSpecialization extends SeqTransform {
    val inputForm = LowForm
    val outputForm = LowForm
    val transforms = Seq(DefineBUFGCE, ReplaceAbstractClockGates)
  }
}
