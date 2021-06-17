// See LICENSE for license details.

package midas.targetutils

import chisel3.{dontTouch, Data}

object PassthroughBlocker {
  /**
    * Injects an identity logic function to prevent PromotePassthroughPaths
    * from extracting a channel fanout that has outputs in different clock domains.
    *
    * Use Case: In the rare event you need to have a direct connection~(i.e.,
    * no additional combinational logic) from a bridge in one clock domain to
    * a bridge in a second clock domain, you _must_ apply this to all
    * passthrough connections on that path.
    *
    * @param target The potentially passthrough signal
    */
  def apply[T <: Data](target: T): T = {
    val inverted = ~(target.asUInt)
    dontTouch(inverted)
    (~inverted).asTypeOf(target)
  }
}

