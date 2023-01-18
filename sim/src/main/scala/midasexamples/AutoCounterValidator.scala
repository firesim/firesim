//See LICENSE for license details.

package firesim.midasexamples


import midas.targetutils.{PerfCounterOps, PerfCounterOpType}
import midas.widgets.{AutoCounterConsts, EventMetadata}

import chisel3._
import chisel3.util.experimental.BoringUtils

import scala.collection.mutable

/**
  * This file contains utilites for doing integration testing of AutoCounter features.
  * These tests consists of wrapping calls to PerfCounter to collect metadata about each counter,
  * and synthesizing printfs to produce expected output.
  *
  * This is achieved by passing an instance of [[AutoCounterValidator]] between modules
  * that mixin [[AutoCounterTestContext]]. See [[AutoCounterModule]] for an example.
  */

object AutoCounterVerificationConstants {
  // This is repeated from the C++ since it is not used elsewhere in the Scala.
  val expectedCSVVersion = 1
  val headerLines = 7
}

/**
  * Tracks all the required metadata to generate reference hardware for a single AutoCounter event.
  */
case class PerfCounterInstance(
    target: chisel3.UInt,
    label: String,
    description: String,
    opType: PerfCounterOpType,
    eventWidth: Int,
    wiringId: String,
    instPath: Seq[String]) extends AutoCounterConsts {

  /** Implements a target-RTL equivalent counter to what would be implemented in the bridge.
    * Returns the generated Counter (_1), and the event that was annotated (_2)
    */
  def generateReferenceHardware(): (UInt, UInt) = {
    val sink = WireDefault(0.U(eventWidth.W))
    BoringUtils.addSink(sink, wiringId)
    dontTouch(sink)
    opType match {
      case PerfCounterOps.Accumulate =>
        (freechips.rocketchip.util.WideCounter(counterWidth, sink).value, sink)
      case PerfCounterOps.Identity =>
        (sink.pad(counterWidth), sink)
    }
  }

  /* Adds a formatted path to the label to match the behavior of the transform */
  def pathPrefixedLabel: String = ((instPath.reverse) :+ label).mkString("_")

  /* Quotes the description escapes potentially troublesome characters */
  def quoteDescriptionForCSV: String =
    '"' + description.replaceAll("\"", "\"\"") + '"'

}

/**
  * Used to collect information about PerfCounters as they are instantiated
  * throughout a DUT's module hierarchy.  Emits validation printfs at the
  * top-level, using the WiringTransform to bring out the events and drive
  * reference counters.
  *
  * @param domainName Name of the clock domain managed by validator
  * @param printfPrefix Used filter simulation output for validation lines
  * @param autoCounterPrintfMode Set when the autoCounter transform emits
  *        synthesizable printfs instead of the AutoCounterBridge
  * @param clockDivision Used to scale validation output, since autocounters in
  *        slower domains will appear to be sampled less frequently (in terms of local
  *        cycle count).
  * @param samplePeriodBase The expected period between AC samples in the base clock domain.
  */
class AutoCounterValidator(
    domainName: String = "BaseClock",
    printfPrefix: String = "AUTOCOUNTER_PRINT ",
    autoCounterPrintfMode: Boolean = false,
    clockDivision: Int = 1,
    clockMultiplication: Int = 1,
    samplePeriodBase: Int = 1000
  ) extends InstanceNameHelper with AutoCounterConsts {

  // Auto Counter currently uses the top module name as the final substring in
  // the label path. Set this top-module name upon instantiation, since we must
  // be instantiated at the top of the module hierarchy to work correctly.
  setModuleName(Module.currentModule.get.getClass.getSimpleName)

  private val samplePeriod = samplePeriodBase / clockDivision

  private val _instances = new mutable.ArrayBuffer[PerfCounterInstance]()

  // Using our current AutoCounter instance should suffice to produce a unique wiring ID.
  private def nextWiringId(): String = s"${domainName}_${_instances.size}"


  /**
    * Registers a new PerfCounter. Parameters to this function mirror PerfCounter parameters.
    *
    */
  def registerEvent (
      target: chisel3.UInt,
      label: String,
      description: String,
      opType: PerfCounterOpType,
      addPathToLabel: Boolean = true): Unit = {
    val wiringId = nextWiringId
    BoringUtils.addSource(target, wiringId)
    _instances += PerfCounterInstance(target, label, description, opType, target.getWidth, wiringId, currentInstPath)
  }

  // For validation, spoof an event that behaves like the implicit cycle count
  // (there is no associated AutoCounterAnnotation for it)
  private def addCycleCount(): Unit = {
    val dummy = WireDefault(true.B)
    val EventMetadata(_,label,description,width,opType) = EventMetadata.localCycleCount
    PerfCounterInstance(dummy, label, description, opType, width, "UNUSED", Seq()) +=: _instances
  }

  // Creates a printf with the validation prefix so it can be extracted from
  // the simulators stdout.
  private def prefixed_printf(fmtString: String, args: Bits*) =
    printf(s"${printfPrefix}${fmtString}\n", args:_*)

  // Emits a csv header row to match the expected output of the driver. Note:
  // columns may be swizzled.
  private def print_header_row(name: String, extractor: PerfCounterInstance => String): Unit =
    prefixed_printf((name +: _instances.map(extractor)).mkString(","))

  /**
    * Generate printfs that mirrors the expected output from the bridge driver.
    *
    * Note: columns in this output will likely be swizzled vs the
    * driver-generated form.  The ScalaTest code de-swizzles by matching
    * labels.,
    */
  def standardModeValidation(): Unit = {
    val sampleCount, localCycles = Reg(UInt(64.W))
    localCycles := localCycles + 1.U
    val baseCycles = (sampleCount + 1.U) * samplePeriodBase.U

    // Generate the validation hardware first, then spoof the cycle counter,
    // since it's behavior under reset does not match a conventional auto
    // counter (it still increments).
    val counters = (baseCycles +: localCycles +: _instances.map(_.generateReferenceHardware._1)).toSeq
    addCycleCount()

    // Wait to print the header until the cycle before the first data row
    // to avoid getting masked off
    when(localCycles === (samplePeriod - 1).U) {
      prefixed_printf(s"version,${AutoCounterVerificationConstants.expectedCSVVersion}")
      prefixed_printf(s"Clock Domain Name, ${domainName}, Base Multiplier, ${clockMultiplication}, Base Divisor, ${clockDivision}")
      print_header_row("label", { _.pathPrefixedLabel })
      // First column is quoted to be consistent across the whole row
      print_header_row("\"description\"", { _.quoteDescriptionForCSV });
      print_header_row("type", { _.opType.toString });
      print_header_row("event width", {_.eventWidth.toString });
      print_header_row("accumulator width", {_ => counterWidth.toString } );
    }

    when ((localCycles >= (samplePeriod - 1).U) && (localCycles % samplePeriod.U === 0.U)) {
      prefixed_printf(Seq.fill(counters.size)("%d").mkString(","), counters:_*)
      sampleCount := sampleCount + 1.U
    }
  }

  def printfModeValidation(): Unit = {
    val localCycles = Reg(UInt(64.W))
    localCycles := localCycles + 1.U

    val eventTuples = _instances.map(_.generateReferenceHardware)
    for (((counter, input), metadata) <- eventTuples.zip(_instances)) {
      val commonFormat = s"CYCLE: %d [AutoCounter] ${metadata.label}: %d"

      metadata.opType match {
        case PerfCounterOps.Accumulate =>
          when (input =/= 0.U) {
            prefixed_printf(commonFormat, localCycles, counter)
          }

        case PerfCounterOps.Identity =>
          when (input =/= RegNext(input)) {
            prefixed_printf(commonFormat, localCycles, input)
          }
      }
    }
  }

  def generateValidationPrintfs(): Unit = {
    if (autoCounterPrintfMode) printfModeValidation else standardModeValidation
  }
}

object AutoCounterWrappers {
  /**
    * Wraps the standard companion object to capture metadata about each
    * PerfCounter invocation in a sideband (AutoCounterValidator). This will be
    * used to generate validation printfs at the end of module elaboration.
    */
  object PerfCounter {
    def apply(
        target: chisel3.UInt,
        label: String,
        description: String,
        opType: PerfCounterOpType = PerfCounterOps.Accumulate)(
        implicit v: AutoCounterValidator): Unit = {
     midas.targetutils.PerfCounter(target, label, description)
     v.registerEvent(target, label, description, opType)
    }

    def identity(
        target: chisel3.UInt,
        label: String,
        description: String)(implicit v: AutoCounterValidator): Unit = {
      midas.targetutils.PerfCounter.identity(target, label, description)
      v.registerEvent(target, label, description, PerfCounterOps.Identity)
    }
  }

  /**
    * As above, wraps calls to to freechips.rocketchip.util.property.cover, to capture
    * the required metadata to generate a reference counter.
    */
  object cover {
    def apply(target: Bool, label: String, description: String)(implicit v: AutoCounterValidator): Unit = {
      freechips.rocketchip.util.property.cover(target, label, description)
      v.registerEvent(target, label, description, PerfCounterOps.Accumulate)
    }
  }
}

/**
  * Mix into any module that has AutoCounters contained within its module hierarchy. 
  */
trait AutoCounterTestContext { this: Module =>
  def instName: String
  implicit val v: AutoCounterValidator
  v.setModuleName(instName)
}

