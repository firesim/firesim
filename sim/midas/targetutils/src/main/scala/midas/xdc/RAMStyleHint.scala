// See LICENSE for license details.

package midas.targetutils.xdc

import chisel3.experimental.ChiselAnnotation
import firrtl.annotations.ReferenceTarget

sealed trait RAMStyle

/**
  * Some rough guidance, based on Ultrascale+, is provided in the scala doc for
  * each hint. Consult the Xilinx UGs for your target architecture and the
  * synthesis UG (UG901).
  */
object RAMStyles {
  /**
    *  From UG901 (v2020.2): Instructs the tool to use the UltraScale+ URAM primitives.
    *
    *  URAMs are 288Kb, 72b wide, 4096 deep.
    */
  case object ULTRA extends RAMStyle

  /**
    *  From UG901 (v2020.2): Instructs the tool to infer RAMB type components
    *
    *  In Ultrascale+ (and older families), BRAMs are 36Kb, and have flexible aspect
    *  ratio: 1b x 64K to 72b x 512
    */
  case object BRAM extends RAMStyle

  /** From UG901 (v2020.2): Instructs the tool to infer registers instead of RAMs. */
  case object REGISTERS extends RAMStyle

  /** From UG901 (v2020.2): Instructs the tool to infer the LUT RAMs. */
  case object DISTRIBUTED extends RAMStyle

  /** Introduced in v2020.2. From UG901 (v2020.2):
    *
    * Instructs the tool to infer a combination of RAM types designed to minimize the
    * amount of space that is unused.
    *
    * Note: This should probably be avoided for non-emulation-class
    * FPGAs (e.g., VU19P), which tend to have rich embedded memory resources, as
    * these designs tend to be under heavy LUT pressure. Here sparsely using
    * BRAM / URAM resources insead of a space-optimal hybrid is desirable.
    */
  case object MIXED extends RAMStyle
}

object RAMStyleHint {
  // _reg suffix is applied to memory cells by Vivado, the glob manages
  // duplication for multibit memories.
  private [midas] def propertyTemplate(style: RAMStyle): String =
    s"set_property RAM_STYLE ${style} [get_cells {}_reg*]"

  private def annotate(style: RAMStyle, rT: =>ReferenceTarget): Unit = {
    chisel3.experimental.annotate(new ChiselAnnotation {
      def toFirrtl = XDCAnnotation(
        XDCFiles.Synthesis,
        propertyTemplate(style),
        rT)
    })
  }

  /**
    * Annotates a chisel3 Mem indicating it should be implemented with a particular
    * Xilinx RAM structure.
    */
  def apply(mem: chisel3.MemBase[_], style: RAMStyle): Unit = {
    annotate(style, mem.toTarget)
  }

  /**
    * Annotates a FIRRTL ReferenceTarget indicating it should be implemented with a particular
    * Xilinx RAM structure.
    * 
    * Note: the onus is on the user to ensure the RT points at a mem-like structure. In general, 
    * one should prefer using the apply method that accepts a chisel3.MemBase[_] to get compile-time errors.
    */
  def apply(mem: =>ReferenceTarget, style: RAMStyle): Unit = {
    annotate(style, mem)
  }
}
