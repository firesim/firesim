// See LICENSE for license details.

package midas
package passes

import midas.core._

import firrtl._
import firrtl.annotations._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.transforms.{DedupModules, DeadCodeElimination}
import Utils._
import java.io.{File, FileWriter}

private[passes] class WCircuit(
  info: Info,
  modules: Seq[DefModule],
  main: String,
  val sim: SimWrapperIO) extends Circuit(info, modules, main)

object MidasAnnotation {
  def apply(t: String, conf: File, json: File, lib: Option[File]) =
    Annotation(CircuitName(t), classOf[MidasTransforms],
               s"$conf $json %s".format(lib map (_.toString) getOrElse ""))
  private val matcher = "([^ ]+) ([^ ]+) ([^ ]*)".r
  def unapply(a: Annotation) = a match {
    case Annotation(CircuitName(c), t, matcher(conf, json, lib))
      if t == classOf[MidasTransforms] =>
        Some(c, new File(conf), new File(json), if (lib.isEmpty) None else Some(new File(lib)))
    case _ => None
  }
}

private[midas] class MidasTransforms(
    io: Seq[(String, chisel3.Data)])
    (implicit param: freechips.rocketchip.config.Parameters) extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  val dir = param(OutputDir)
  def execute(state: CircuitState) = (getMyAnnotations(state): @unchecked) match {
    case Seq(MidasAnnotation(state.circuit.main, conf, json, lib)) =>
      val xforms = Seq(
        firrtl.passes.RemoveValidIf,
        new firrtl.transforms.ConstantPropagation,
        firrtl.passes.SplitExpressions,
        firrtl.passes.CommonSubexpressionElimination,
        new firrtl.transforms.DeadCodeElimination,
        new ConfToJSON(conf, json),
        new barstools.macros.MacroCompilerTransform,
        firrtl.passes.ResolveKinds,
        firrtl.passes.RemoveEmpty,
        // NB: Carelessly removing this pass will break the FireSim manager as we always
        // need to generate the *.asserts file. Fix by baking into driver.
        new AssertPass(dir),
        new PrintSynthesis(dir),
        new Fame1Transform(Some(lib getOrElse json)),
        new strober.passes.StroberTransforms(dir, lib getOrElse json)) ++
      // Any subFields must be flattened out beforing linking in HighForm constructs must Lower 
      firrtl.CompilerUtils.getLoweringTransforms(HighForm, LowForm) ++ Seq(
        new SimulationMapping(io),
        new PlatformMapping(state.circuit.main, dir)
      )
      (xforms foldLeft state)((in, xform) =>
        xform runTransform in).copy(form=outputForm)
  }
}

/**
  * An annotation on a module indicating it should be fame1 tranformed using
  * a Bool, whose name is indicated by tFire, used as the targetFire signal
  */
case class Fame1ChiselAnnotation(target: chisel3.experimental.RawModule, tFire: String = "targetFire") 
    extends chisel3.experimental.ChiselAnnotation {
  def toFirrtl = Fame1Annotation(target.toNamed, tFire)
}

case class Fame1Annotation(target: ModuleName, tFire: String) extends
    SingleTargetAnnotation[ModuleName] {
  def duplicate(n: ModuleName) = this.copy(target = n)
}

class Fame1Instances extends Transform {
  def inputForm = LowForm
  def outputForm = HighForm
  def execute(state: CircuitState): CircuitState = {
    val fame1s = (state.annotations.collect { case Fame1Annotation(ModuleName(m, c), tFire) => m -> tFire }).toMap
    state.copy(circuit = new ModelFame1Transform(fame1s).run(state.circuit))
  }
}

/* Instead of passing a data structure between pre-FAME target-transforming passes 
 * Add NoTargetAnnotations that can regenerate a chisel type from a HighForm port
 *
 * Initially i tried extending SingleTargetAnnotation but we need a LowForm
 * inner circuit to do linking (if the wrapping circuit is LowForm), and thus the target
 *  will have lost its subFields when we go to regenerate the ChiselIO.
 */
trait AddedTargetIoAnnotation[T <: chisel3.Data] extends Annotation {
  def generateChiselIO(): Tuple2[String, T]
}
