// See LICENSE for license details.

package midas.targetutils

import chisel3._
import chisel3.stage.ChiselStage

import org.scalatest.flatspec.AnyFlatSpec

trait ElaborationUtils { self: AnyFlatSpec =>
  def elaborate(mod: =>Module): Unit = ChiselStage.emitFirrtl(mod)

  class AnnotateChiselTypeModule[T <: Data](gen: =>T, annotator: T => Unit) extends Module {
    val io = IO(new Bundle {
      val test = gen
      annotator(test)
    })
  }
  class AnnotateHardwareModule[T <: Data](gen: =>T, annotator: T => Unit) extends Module {
    val io = IO(new Bundle {
      val test = gen
    })
    annotator(io.test)
  }

  /**
    * Instantiates a module with IO of the provided type twice, checking that the provided
    * annotator croaks when used before the IO is still unbound, but not otherwise.
    *
    */
  def checkBehaviorOnUnboundTargets[T <: Data](gen: =>T, annotator: T => Unit) { 
    it should "elaborate and compile correctly when annotating HW types" in {
      elaborate(new AnnotateHardwareModule(gen, annotator))
    }

    it should "reject unbound types at elaboration time" in {
      assertThrows[ExpectedHardwareException] {
        elaborate(new AnnotateChiselTypeModule(gen, annotator))
      }
    }
  }
}

