// See LICENSE for license details.
package midas.unittest

import chisel3._
import chisel3.experimental.DataMirror

import org.chipsalliance.cde.config.{Config, Field, Parameters}
import midas.widgets.ScanRegister

case object QoRTargets extends Field[Parameters => Seq[RawModule]]
class QoRShim(implicit val p: Parameters) extends Module {
  val io = IO(new Bundle {
    val scanIn     = Input(Bool())
    val scanOut    = Output(Bool())
    val scanEnable = Input(Bool())
  })

  val modules  = p(QoRTargets)(p)
  val scanOuts = modules.map({ module =>
    val ports = DataMirror
      .modulePorts(module)
      .flatMap({
        case (_, _: Clock) => None
        case (_, id)       => Some(id)
      })
    ScanRegister(ports, io.scanEnable, io.scanIn)
  })
  io.scanOut := scanOuts.reduce(_ || _)
}

class Midas2QoRTargets
    extends Config((_, _, _) => { case QoRTargets =>
      (q: Parameters) => {
        implicit val p = q
        Seq(
          Module(new midas.models.sram.AsyncMemChiselModel(160, 64, 6, 3))
        )
      }
    })
