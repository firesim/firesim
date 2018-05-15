package firesim
package passes

import firrtl._
import firrtl.ir._
import firrtl.Utils.zero

// Remove RocketChip's plusarg_reader
object PlusArgReaderPass extends firrtl.passes.Pass {
  override def name = "[midas top] PlusArgReader Pass"
  override def inputForm = MidForm
  override def outputForm = MidForm

  def run(c: Circuit): Circuit = {
    c.copy(modules = c.modules map {
      case m: Module => m
      case m: ExtModule if m.defname == "plusarg_reader" =>
        Module(m.info, m.name, m.ports, Block(Seq(
          // IsInvalid(NoInfo, WRef("out"))
          Connect(NoInfo, WRef("out"), zero)
        )))
      case m: ExtModule => m
    })
  }
} 
