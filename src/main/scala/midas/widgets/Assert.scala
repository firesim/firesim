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

class AssertWidget(numAsserts: Int)(implicit p: Parameters) extends EndpointWidget()(p) {
  val io = IO(new WidgetIO())
  val hPort = IO(HostPort(Input(UInt(numAsserts.W))))
  val resume = WireInit(false.B)
  val cycles = RegInit(0.U(64.W))
  val asserts = hPort.hBits
  val assertId = PriorityEncoder(asserts)
  val assertFire = asserts.orR

  val stallN = (!assertFire || resume)
  val dummyPredicate = true.B

  val tFireHelper = DecoupledHelper(hPort.toHost.hValid, stallN, dummyPredicate)
  val targetFire = tFireHelper.fire() // FIXME: On next RC bump
  hPort.toHost.hReady := tFireHelper.fire(hPort.toHost.hValid)
  // We only sink tokens, so tie off the return channel
  hPort.fromHost.hValid := true.B
  when (targetFire) {
    cycles := cycles + 1.U
  }

  genROReg(assertId, "id")
  genROReg(assertFire, "fire")
  // FIXME: no hardcode
  genROReg(cycles(31, 0), "cycle_low")
  genROReg(cycles >> 32, "cycle_high")
  Pulsify(genWORegInit(resume, "resume", false.B), pulseLength = 1)
  genCRFile()
}
