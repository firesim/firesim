//See LICENSE for license details
// See LICENSE for license details.

package firesim
package passes

class AsyncResetReg extends chisel3.Module {
  import chisel3._
  import chisel3.util._
  val en = IO(Input(Bool()))
  val d = IO(Input(Bool()))
  val q = IO(Output(Bool()))
  q := RegEnable(d, false.B, en)
}

// Transform RocketChip's async reset to sync reset
object AsyncResetRegPass extends firrtl.Transform {
  import firrtl._
  import firrtl.ir._
  import firrtl.Mappers._
  import firrtl.transforms._

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

  private val chirrtl = chisel3.stage.ChiselStage.convert(new AsyncResetReg)
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

  def execute(cs: CircuitState): CircuitState = {
    val transformedMods = cs.circuit.modules.map(transform)

    // Drop AsyncResetReg BlackBoxSourceHelper annotations
    val eMods = transformedMods.collect({ case e: ExtModule => e.name }).toSet
    val transformedAnnos = cs.annotations.filter {
      case BlackBoxResourceAnno(t, _) => eMods.contains(t.name)
      case BlackBoxInlineAnno(t, _, _) => eMods.contains(t.name)
      case BlackBoxPathAnno(t, _) => eMods.contains(t.name)
      case _ => true
    }

    cs.copy(circuit = cs.circuit.copy(modules = transformedMods), annotations = transformedAnnos)
  }
} 
