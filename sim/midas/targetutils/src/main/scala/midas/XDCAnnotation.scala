// See LICENSE for license details.

package midas.targetutils

import chisel3._
import chisel3.experimental.{BaseModule, ChiselAnnotation, annotate}

import firrtl.{RenameMap}
import firrtl.annotations._
import firrtl.transforms.DontTouchAllTargets

trait XDCAnnotationConstants {
  val specifierRegex = "\\{}".r
}

/**
  * Encode a string to be emitted to an XDC file with specifiers derived from
  * ReferenceTargets. RTs are specified using "{}" a la python. This makes the
  * emitted XDC more robust against the module hierarchy manipulations (such as
    * promotion/extraction and linking) that golden gate performs.
  *
  * For example:
  *   XDCAnnotation("get_pins -of [get_clocks -hierarchical *{}]", clockRT)
  *
  * Would eventually emit:
  *   get_pins -of [get_clocks -hierarchical *absolute/path/to/clock]
  *
  * Here the emission pass by default creates a full instance path to the
  * reference to avoid multiple matches (in vivado this only really works in
  * implementation). The wildcard is still included if emitted chisel is
  * instantiated in a parent verilog hierarchy (as is the case with the F1
  * shell).  TODO: Provide the enclosing instance path with an
  * annotation/parameter to emit the full absolute path?
  *
  * Restrictions (that can be relaxed in the future, perhaps using different specifiers?):
  *   - There can only be one instance of the target in the complete netlist
  *     - Can't point at stuff in duplicated modules
  *     - Can't point at an aggregates (since it will be lowered to multiple targets)
  *   - No support for Module or Instance Targets
  *
  */

case class XDCAnnotation(formatString: String, argumentList: ReferenceTarget*)
    extends Annotation with XDCAnnotationConstants
    // This is included until we figure out how to gracefully handle deletion.
    with DontTouchAllTargets {
  def update(renames: RenameMap): Seq[firrtl.annotations.Annotation] = {
    val renamer = new ReferenceTargetRenamer(renames)
    Seq(XDCAnnotation(formatString, argumentList.map(a => renamer.exactRename(a)):_*))
  }
}

/**
  * Chisel-side sugar for emitting XDCAnnotations.
  */
object XDC extends XDCAnnotationConstants {
  def apply(formatString: String, argumentList: Data*): Unit = {
    val numArguments = specifierRegex.findAllIn(formatString).size
    require(numArguments == argumentList.size,
      s"Format string requires ${numArguments}, provided ${argumentList.size}")
    chisel3.experimental.annotate(new ChiselAnnotation {
      def toFirrtl = XDCAnnotation(formatString, argumentList.map(_.toTarget):_*)
    })
  }
}
