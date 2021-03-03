
//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.{Field, Parameters, Config}

import midas.widgets.{RationalClockBridge, PeekPokeBridge, RationalClock}

case object NumClockDomains extends Field[Int](3)

class WithNDomains(n: Int) extends Config((site, here, up) => {
  case NumClockDomains => n
})

class D8 extends WithNDomains(8)
class D12 extends WithNDomains(12)
class D16 extends WithNDomains(16)
class D24 extends WithNDomains(24)
class D32 extends WithNDomains(32)
class D48 extends WithNDomains(48)
class D64 extends WithNDomains(64)

/**
  * Creates a scan chain in which each successive stage is placed in a different
  * clock domain.  This serves as a simple test of the scalability of FireSim's multiclock 
  * support.
  *
  * @param A parameters instance with the Field [[NumClockDomains]] set.
  */
class MulticlockRegisterChain(implicit p: Parameters) extends RawModule {

  class RegisterModule extends MultiIOModule {
    def dataType = UInt(32.W)
    val in = IO(Input(dataType))
    val out = IO(Output(dataType))
    out := RegNext(in)
  }

  val reset = WireInit(false.B)
  val clockBridge = Module(new RationalClockBridge(
    Seq.tabulate(p(NumClockDomains)){ i => RationalClock(s"DivBy$i", 1, i) }
  ))

  val regMods = clockBridge.io.clocks.map { clock =>
    withClockAndReset(clock, reset) {
      val regMod = Module(new RegisterModule())
      regMod
    }
  }

  def connect(mods: List[RegisterModule]): Unit = mods match {
    case modA :: Nil => Nil
    case modA :: mods =>
      mods.head.in := modA.out
      connect(mods)
  }
  connect(regMods.toList)

  withClock(clockBridge.io.clocks.head) {
    val peekPokeBridge = PeekPokeBridge(clockBridge.io.clocks.head,
                                        reset,
                                        ("in", regMods.head.in),
                                        ("out", regMods.last.out))
  }
}
