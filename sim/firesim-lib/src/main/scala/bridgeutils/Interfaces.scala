// See LICENSE for license details.

package firesim.lib.bridgeutils

import chisel3._

class HostReadyValid extends Bundle {
  val hReady     = Input(Bool())
  val hValid     = Output(Bool())
  def fire: Bool = hReady && hValid
}
