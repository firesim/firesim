// See LICENSE for license details.

package midas.targetutils

import chisel3.experimental.{ChiselAnnotation}
import firrtl.annotations.ReferenceTarget
import midas.targetutils.xdc.{XDCFiles, XDCAnnotation}

sealed trait RAMStyle

object RAMStyles {
  case object ULTRA extends RAMStyle
  case object BRAM extends RAMStyle
  case object REGISTERS extends RAMStyle
  case object DISTRIBUTED extends RAMStyle
}

object RAMStyleHint {
  // _reg suffix is applied to memory cells by Vivado, the glob manages
  // duplication for multibit memories.
  private def propertyTemplate(style: RAMStyle): String =
    s"set_property RAM_STYLE ${style} [get_cells {}_reg*]"

  private def annotate(style: RAMStyle, rT: =>ReferenceTarget): Unit = {
    chisel3.experimental.annotate(new ChiselAnnotation {
      def toFirrtl = XDCAnnotation(
        XDCFiles.Synthesis,
        propertyTemplate(style),
        rT)
    })
  }

  /*
   * TODO: These aren't going to work very well for aggregate types. While mems will
   * be helped by the FPGA backend, Vecs will still be split. Should i drop the hint
   * on Vec of aggregate? Remove the method altogether?
   */
  def apply(mem: chisel3.Mem[_], style: RAMStyle): Unit = annotate(style, mem.toTarget)
  def apply(vec: chisel3.Vec[_], style: RAMStyle): Unit = annotate(style, vec.toTarget)
}
