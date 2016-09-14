package strober
package passes

import firrtl._
import firrtl.Annotations.AnnotationMap
import scala.collection.mutable.HashMap
import scala.collection.immutable.ListSet
import scala.util.DynamicVariable

private class TransformContext {
  val childInsts = HashMap[String, ListSet[String]]()
  val childMods = HashMap[String, ListSet[String]]()
  val instToMod = HashMap[(String, String), String]()
  val chains = Map(
    ChainType.Trace -> HashMap[String, Seq[ir.Statement]](),
    ChainType.Regs  -> HashMap[String, Seq[ir.Statement]](),
    ChainType.SRAM  -> HashMap[String, Seq[ir.Statement]](),
    ChainType.Cntr  -> HashMap[String, Seq[ir.Statement]]()
  )
}

private[strober] object StroberTransforms extends Transform with SimpleRun {
  private val contextVar = new DynamicVariable[Option[TransformContext]](None)
  private[passes] def context = contextVar.value.getOrElse (new TransformContext)
  val passSeq = Seq(
    Analyses,
    Fame1Transform,
    AddDaisyChains,
    DumpChains
  )
  def execute(circuit: ir.Circuit, annotationMap: AnnotationMap) =
    (contextVar withValue Some(new TransformContext))(run(circuit, passSeq))
}
