// See LICENSE for license details.

package midas
package widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}

import scala.collection.immutable.ListMap

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.util.{DecoupledHelper}
import junctions._

import midas.core.{HostPort}


class AssertBundle(val numAsserts: Int) extends Bundle {
  val asserts = Output(UInt(numAsserts.W))
}

class AssertWidgetIO(val numAsserts: Int)(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val hPort = Flipped(HostPort(new AssertBundle(numAsserts)))
}

class AssertBundleEndpoint extends Endpoint {
  var numAsserts = 0
  var initialized = false
  def matchType(data: Data) = data match {
    case channel: AssertBundle =>
      require(DataMirror.directionOf(channel) == Direction.Output, "AssertBundle has unexpected direction")
      // Can't do this as matchType is invoked multiple times
      //require(!initialized, "Can only match on one instance of AssertBundle")
      initialized = true
      numAsserts = channel.numAsserts
      true
    case _ => false
  }
  def widget(p: Parameters) = {
    require(initialized, "Attempted to generate an AssertWidget before inspecting input data bundle")
    new AssertWidget(numAsserts)(p)
  }
  override def widgetName = "AssertionWidget"
}

class AssertWidget(numAsserts: Int)(implicit p: Parameters) extends EndpointWidget()(p) with HasChannels {
  val io = IO(new AssertWidgetIO(numAsserts))
  val resume = WireInit(false.B)
  val cycles = RegInit(0.U(64.W))
  val tResetAsserted = RegInit(false.B)
  val asserts = io.hPort.hBits.asserts
  val assertId = PriorityEncoder(asserts)
  val assertFire = asserts.orR && tResetAsserted && !io.tReset.bits

  val stallN = (!assertFire || resume)
  val dummyPredicate = true.B

  val tFireHelper = DecoupledHelper(io.hPort.toHost.hValid, io.tReset.valid, stallN, dummyPredicate)
  val targetFire = tFireHelper.fire(dummyPredicate) // FIXME: On next RC bump
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

  genROReg(assertId, "id")
  genROReg(assertFire, "fire")
  // FIXME: no hardcode
  genROReg(cycles(31, 0), "cycle_low")
  genROReg(cycles >> 32, "cycle_high")
  Pulsify(genWORegInit(resume, "resume", false.B), pulseLength = 1)
  genCRFile()
}
