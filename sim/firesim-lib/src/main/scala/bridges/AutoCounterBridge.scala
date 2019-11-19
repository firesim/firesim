package firesim
package bridges

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util._

import midas.widgets._
import testchipip.{StreamIO, StreamChannel}


class AutoCounterBundle(val numCounters: Int) extends Bundle {
  val counters = Input(Vec(numCounters, UInt(64.W)))
}


class AutoCounterBridgeModule(numCounters: Int, labels: scala.collection.mutable.Map[String, String])(implicit p: Parameters) extends BridgeModule[HostPortIO[AutoCounterBundle]]()(p) {
  val io = IO(new WidgetIO())
  val hPort = IO(Flipped(HostPort(new AutoCounterBundle(numCounters))))
  val cycles = RegInit(0.U(64.W))


  val tFireHelper = DecoupledHelper(hPort.toHost.hValid)
  val targetFire = tFireHelper.fire()
  hPort.toHost.hReady := tFireHelper.fire(hPort.toHost.hValid)
  // We only sink tokens, so tie off the return channel
  hPort.fromHost.hValid := true.B
  when (targetFire) {
    cycles := cycles + 1.U
  }

  labels.keys.zip(hPort.hBits.counters).foreach {
    case(label, counter) => {
      genROReg(counter(31, 0), s"${label}_counter_low")
      genROReg(counter >> 32, s"${label}_counter_high")
    }
  }

  genROReg(cycles(31, 0), "cycles_low")
  genROReg(cycles >> 32, "cycles_high")

  genCRFile()
}
