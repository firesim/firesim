//See LICENSE for license details
// See LICENSE for license details.

package firesim
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._

class AsyncResetReg extends chisel3.experimental.MultiIOModule {
  import chisel3.core._
  import chisel3.util._
  val en = IO(Input(Bool()))
  val d = IO(Input(Bool()))
  val q = IO(Output(Bool()))
  q := RegEnable(d, false.B, en)
}

// Transform RocketChip's async reset to sync reset
object AsyncResetRegPass extends firrtl.passes.Pass {
  override def name = "[FireSim] Replace AsyncReset Pass"
  override def inputForm = MidForm
  override def outputForm = MidForm

  def renameClockResetExp(e: Expression): Expression =
    e map renameClockResetExp match {
      case e: WRef if e.name == "clock" => e.copy(name = "clk")
      case e: WRef if e.name == "reset" => e.copy(name = "rst")
      case e => e
    }

  def renameClockReset(s: Statement): Statement =
    s map renameClockResetExp map renameClockReset

  private val chirrtl = Parser.parse(chisel3.Driver.emit(() => new AsyncResetReg))
  private val circuit = (new MiddleFirrtlCompiler).compile(
    CircuitState(chirrtl, ChirrtlForm), Nil).circuit
  private val module = (circuit.modules collect {
    case m: Module => m.copy(
      ports = m.ports map (p => p.name match {
        case "clock" => p.copy(name = "clk")
        case "reset" => p.copy(name = "rst")
        case _ => p
      }),
      body = m.body map renameClockReset
    )
  }).head

  def transform(m: DefModule): DefModule =
    m match {
      case m: ExtModule if m.defname == "AsyncResetReg" =>
        module.copy(info=m.info, name=m.name)
      case _ => m
    } 

  def run(c: Circuit): Circuit = {
    c.copy(modules = c.modules map transform)
  }
} 
