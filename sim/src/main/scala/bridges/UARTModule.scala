//See LICENSE for license details.

package firesim.bridges

import chisel3._

import firesim.midasexamples.PeekPokeMidasExampleHarness
import freechips.rocketchip.config.Parameters

import sifive.blocks.devices.uart.UARTParams

class UARTDUT(implicit val p: Parameters) extends Module {
  val uartParams = UARTParams(address = 0x10013000)

  val ep = Module(new UARTBridge(uartParams))
  ep.io.reset    := reset
  ep.io.clock    := clock
  ep.io.uart.txd := ep.io.uart.rxd
}

class UARTModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new UARTDUT)
