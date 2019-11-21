package firesim
package bridges

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}
import chisel3.util.experimental.BoringUtils
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util._

import midas.widgets._
import testchipip.{StreamIO, StreamChannel}


class AutoCounterBundle(val numCounters: Int) extends Bundle {
  val counters = Input(Vec(numCounters, UInt(64.W)))
}

case class AutoCounterBridgeConstArgs(numcounters: Int, autoCounterPortsMap: scala.collection.mutable.Map[String, String], hastracerwidget: Boolean = false)

class AutoCounterBridgeModule(constructorArg: AutoCounterBridgeConstArgs)(implicit p: Parameters) extends BridgeModule[HostPortIO[AutoCounterBundle]]()(p) {

  val numCounters = constructorArg.numcounters
  val labels = constructorArg.autoCounterPortsMap
  val hastracerwidget = constructorArg.hastracerwidget
  val trigger = WireDefault(true.B)

  val io = IO(new WidgetIO())
  val hPort = IO(HostPort(new AutoCounterBundle(numCounters)))
  val cycles = RegInit(0.U(64.W))
  val cycles_since_trigger = RegInit(0.U(64.W))
  val acc_counters = RegInit(VecInit(Seq.fill(numCounters)(0.U(64.W))))

  hastracerwidget match {
      case true => BoringUtils.addSink(trigger, s"trace_trigger")
      case _ => trigger := true.B
   }


  val tFireHelper = DecoupledHelper(hPort.toHost.hValid, hPort.fromHost.hReady)
  val targetFire = tFireHelper.fire()
  hPort.toHost.hReady := tFireHelper.fire(hPort.toHost.hValid)
  // We only sink tokens, so tie off the return channel
  hPort.fromHost.hValid := true.B
  when (targetFire) {
    cycles := cycles + 1.U
    hPort.hBits.counters.zip(acc_counters).foreach {
      case(counter, acc) => acc := counter
    }
  }
  when (targetFire && trigger) {
    cycles_since_trigger := cycles_since_trigger + 1.U
  }

  //labels.keys.zip(hPort.hBits.counters).foreach {
  labels.keys.zip(acc_counters).foreach {
    case(label, counter) => {
      //genROReg(counter(31, 0), s"autocounter_low_${label}")
      //genROReg(counter >> 32, s"autocounter_high_${label}")
      genROReg(counter(31, 0), s"autocounter_low_${labels(label)}")
      genROReg(counter >> 32, s"autocounter_high_${labels(label)}")
    }
  }

  genROReg(cycles(31, 0), "cycles_low")
  genROReg(cycles >> 32, "cycles_high")
  genROReg(cycles_since_trigger(31, 0), "cycles_since_trigger_low")
  genROReg(cycles_since_trigger >> 32, "cycles_since_trigger_high")

  genCRFile()
}
