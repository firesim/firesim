//See LICENSE for license details
// See LICENSE for license details.

package firesim
package passes

import firrtl._
import firrtl.ir._
import firrtl.Utils.zero

// Remove RocketChip's plusarg_reader
object PlusArgReaderPass extends firrtl.Transform {
  override def name = "[FireSim] PlusArgReader Pass"
  def inputForm = MidForm
  def outputForm = MidForm

  def execute(cs: CircuitState): CircuitState = {
    val c = cs.circuit
    cs.copy(circuit = c.copy(modules = c.modules map {
      case m: Module => m
      case m: ExtModule if m.defname == "plusarg_reader" => {
        val default = m.params.find(_.name == "DEFAULT")
                              .map(_.asInstanceOf[IntParam].value)
                              .getOrElse(BigInt(0))
        val literal = UIntLiteral(default, IntWidth(32))
        Module(m.info, m.name, m.ports, Block(Seq(
          // IsInvalid(NoInfo, WRef("out"))
          Connect(NoInfo, WRef("out"), literal)
        )))
      }
      case m: ExtModule => m
    }))
  }
}
