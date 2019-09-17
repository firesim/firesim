package firesim
package endpoints

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util._

import midas.core.{HostPort}
import midas.widgets._
import testchipip.{StreamIO, StreamChannel}


class AutoCounterBundle(val numCounters: Int) extends Bundle {
  val counters = Input(Vec(numCounters, UInt(64.W)))
}

/*
class AutoCounterWidgetIO(val numCounters: Int)(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val hPort = Flipped(HostPort(new AutoCounterBundle(numCounters)))
}

class AutoCounterBundleEndpoint extends Endpoint {
  var numCounters = 0
  var initialized = false
  def matchType(data: Data) = data match {
    case channel: AutoCounterBundle =>
      require(DataMirror.directionOf(channel) == Direction.Output, "AutoCounter has unexpected direction")
      // Can't do this as matchType is invoked multiple times
      //require(!initialized, "Can only match on one instance of AutoCounterBundle")
      initialized = true
      numCounters = channel.numCounters
      true
    case _ => false
  }
  def widget(p: Parameters) = {
    require(initialized, "Attempted to generate an AutoCounterWidget before inspecting input data bundle")
    new AutoCounterWidget(numCounters)(p)
  }
  override def widgetName = "AutoCounterWidget"
}
*/

class AutoCounterWidget(numCounters: Int, labels: Seq[String])(implicit p: Parameters) extends EndpointWidget()(p) {
  //val io = IO(new AutoCounterWidgetIO(numCounters))
  val io = Flipped(HostPort(new AutoCounterBundle(numCounters)))
  val resume = WireInit(false.B)
  val cycles = RegInit(0.U(64.W))
  val tResetAsserted = RegInit(false.B)


  val tFireHelper = DecoupledHelper(io.hPort.toHost.hValid, io.tReset.valid)
  val targetFire = tFireHelper.fire()
  io.tReset.ready := tFireHelper.fire(io.tReset.valid)
  io.hPort.toHost.hReady := tFireHelper.fire(io.hPort.toHost.hValid)
  // We only sink tokens, so tie off the return channel
  io.hPort.fromHost.hValid := true.B
  when (targetFire) {
    cycles := cycles + 1.U
    when (io.tReset.bits) {
      tResetAsserted := true.B
    }
  }

  labels.zip(io.counterios).foreach {
    case(label, counterio) => {
      genROReg(counterio(31, 0), s"$label_counter_low")
      genROReg(counterio >> 32, s"$label_counter_high")
    }
  }

  genROReg(cycles(31, 0), "cycles_low")
  genROReg(cycles >> 32, "cycles_high")

  genCRFile()
}
