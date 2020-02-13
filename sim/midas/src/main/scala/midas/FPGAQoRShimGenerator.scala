// See LICENSE for license details.
package midas.unittest

import chisel3._
import firrtl.{ExecutionOptionsManager, HasFirrtlOptions}

import freechips.rocketchip.config.{Parameters, Config, Field}
import midas.widgets.ScanRegister

case object QoRTargets extends Field[Parameters => Seq[RawModule]]
class QoRShim(implicit val p: Parameters) extends Module {
  val io = IO(new Bundle {
    val scanIn = Input(Bool())
    val scanOut = Output(Bool())
    val scanEnable = Input(Bool())
  })

  val modules = p(QoRTargets)(p)
  val scanOuts = modules.map({ module =>
    val ports = module.getPorts.flatMap({
      case chisel3.internal.firrtl.Port(id: Clock, _) => None
      case chisel3.internal.firrtl.Port(id, _) => Some(id)
    })
    ScanRegister(ports, io.scanEnable, io.scanIn)
  })
  io.scanOut := scanOuts.reduce(_ || _)
}

class Midas2QoRTargets extends Config((site, here, up) => {
  case QoRTargets => (q: Parameters) => {
    implicit val p = q
    Seq(
      Module(new midas.models.sram.AsyncMemChiselModel(160, 64, 6, 3))
    )
  }
})


// Generates synthesizable unit tests for key modules, such as simulation channels
// See: src/main/cc/unittest/Makefile for the downstream RTL-simulation flow
//
// TODO: Make the core of this generator a trait that can be mixed into
// FireSim's ScalaTests for more type safety
object QoRShimGenerator extends App with freechips.rocketchip.util.HasGeneratorUtilities {

 case class QoRShimOptions(
      configProject: String = "midas.unittest",
      config: String = "Midas2QoRTargets") {
    val fullConfigClasses: Seq[String] = Seq(configProject + "." + config)
  }

  trait HasUnitTestOptions {
    self: ExecutionOptionsManager =>
    var qorOptions = QoRShimOptions()
    parser.note("MIDAS Unit Test Generator Options")
    parser.opt[String]("config-project")
      .abbr("cp")
      .valueName("<config-project>")
      .foreach { d => qorOptions = qorOptions.copy(configProject = d) }
    parser.opt[String]("config")
      .abbr("conf")
      .valueName("<configClassName>")
      .foreach { cfg => qorOptions = qorOptions.copy(config = cfg) }
  }

  val exOptions = new ExecutionOptionsManager("qor")
    with HasChiselExecutionOptions
    with HasFirrtlOptions
    with HasUnitTestOptions

  exOptions.parse(args)

  val params = getConfig(exOptions.qorOptions.fullConfigClasses).toInstance
  Driver.execute(exOptions, () => new QoRShim()(params))
}
