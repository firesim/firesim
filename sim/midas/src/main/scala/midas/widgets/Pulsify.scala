// See LICENSE for license details.

package midas
package widgets

import chisel3._
import chisel3.util._

/** Takes a Bool and forces it to deassert after pulseLength cycles by using Chisel last-connect semantics, effectively
  * "stretching" the pulse.
  *
  * @param in
  *   The wire to override
  *
  * @param pulseLength
  *   the number of cycles to stretch the pulse over.
  */
object Pulsify {
  def apply(in: Bool, pulseLength: Int): Unit = {
    require(pulseLength > 0)
    if (pulseLength > 1) {
      val count = Counter(pulseLength)
      when(in) { count.inc() }
      when(count.value === (pulseLength - 1).U) {
        in          := false.B
        count.value := 0.U
      }
    } else {
      when(in) { in := false.B }
    }
  }
}
