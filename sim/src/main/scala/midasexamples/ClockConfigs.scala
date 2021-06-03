//See LICENSE for license details.

package firesim.midasexamples

import freechips.rocketchip.config.{Parameters, Field, Config}

import midas.widgets._


case object NumClockDomains extends Field[Int](6)

class WithNDomains(n: Int) extends Config((site, here, up) => {
  case NumClockDomains => n
})

class D02 extends WithNDomains(2)
class D04 extends WithNDomains(4)
class D06 extends WithNDomains(6)
class D08 extends WithNDomains(8)
class D10 extends WithNDomains(10)
class D12 extends WithNDomains(12)
class D16 extends WithNDomains(16)
class D18 extends WithNDomains(18)
class D20 extends WithNDomains(20)
class D22 extends WithNDomains(22)
class D24 extends WithNDomains(24)
class D28 extends WithNDomains(28)
class D32 extends WithNDomains(32)
class D40 extends WithNDomains(40)
class D48 extends WithNDomains(48)
class D64 extends WithNDomains(64)

case object ClockMuxStyleKey extends Field[midas.widgets.ClockMuxImplStyle](MuxStyle.Baseline)
//case object ClockMuxStyleKey extends Field[midas.widgets.ClockMuxImplStyle](MuxStyle.Mutex(2))
//case object ClockDividerStyleKey extends Field[midas.widgets.ClockDividerImplStyle](BaselineDivider)
case object ClockDividerStyleKey extends Field[midas.widgets.ClockDividerImplStyle](GenericDivider)
