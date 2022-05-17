// See LICENSE for license details.

package midas.targetutils

import chisel3._
import chisel3.stage.ChiselStage

import firrtl.stage.{FirrtlPhase,  FirrtlCircuitAnnotation, RunFirrtlTransformAnnotation}

import org.scalatest.flatspec.AnyFlatSpec

trait ElaborationUtils { self: AnyFlatSpec =>
  /**
    * Elaborates the module returning the CHIRRTL for the circuit, and strictly
    * the annotations produced as a side effect of elaboration.
    */
  def elaborate(mod: =>Module): (firrtl.ir.Circuit, firrtl.AnnotationSeq) = ElaborateChiselSubCircuit(mod)

  /**
    * Our utitilies primarily generate new annotations. This method differs from
    * upstream ChiselStage support in that this returns all annotations so that
    * we may introspect on them and ensure the correctness of targetutils annotations
    * after lowering and deduplication.
    */
  def elaborateAndLower(mod: =>Module): Seq[firrtl.annotations.Annotation] = {
    val (circuit, annos) = elaborate(mod)
    val inputAnnos =
      FirrtlCircuitAnnotation(circuit) ::
      RunFirrtlTransformAnnotation(new firrtl.VerilogEmitter) ::
      annos.toList

    (new FirrtlPhase).transform(inputAnnos)
  }

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

