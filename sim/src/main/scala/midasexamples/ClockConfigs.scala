//See LICENSE for license details.

package firesim.midasexamples

import freechips.rocketchip.config.{Parameters, Field, Config}

import midas.widgets._


case object NumClockDomains extends Field[Int](3)

class WithNDomains(n: Int) extends Config((site, here, up) => {
  case NumClockDomains => n
})

class D2 extends WithNDomains(2)
class D4 extends WithNDomains(4)
class D6 extends WithNDomains(6)
class D8 extends WithNDomains(8)
class D10 extends WithNDomains(10)
class D12 extends WithNDomains(12)
class D16 extends WithNDomains(16)
class D24 extends WithNDomains(24)
class D32 extends WithNDomains(32)
class D48 extends WithNDomains(48)
class D64 extends WithNDomains(64)

//case object ClockMuxStyleKey extends Field[midas.widgets.ClockMuxImplStyle](MuxStyle.Baseline)
case object ClockMuxStyleKey extends Field[midas.widgets.ClockMuxImplStyle](MuxStyle.Mutex(2))
//case object ClockDividerStyleKey extends Field[midas.widgets.ClockDividerImplStyle](BaselineDivider)
case object ClockDividerStyleKey extends Field[midas.widgets.ClockDividerImplStyle](GenericDivider)
