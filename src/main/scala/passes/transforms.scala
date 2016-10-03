package strober
package passes

import firrtl._
import firrtl.passes._
import firrtl.Annotations.{AnnotationMap, CircuitName, TransID}
import scala.collection.mutable.{HashMap, LinkedHashSet, ArrayBuffer}
import scala.util.DynamicVariable

private class TransformContext {
  type ChainMap = HashMap[String, ArrayBuffer[ir.Statement]]
  val childInsts = HashMap[String, ArrayBuffer[String]]()
  val childMods = HashMap[String, LinkedHashSet[String]]()
  val instToMod = HashMap[(String, String), String]()
  val chains = (ChainType.values.toList map (_ -> new ChainMap)).toMap
}

private[strober] object StroberTransforms extends Transform with SimpleRun {
  private val contextVar = new DynamicVariable[Option[TransformContext]](None)
  private[passes] def context = contextVar.value.getOrElse (new TransformContext)
  def execute(circuit: ir.Circuit, map: AnnotationMap) = {
    (contextVar withValue Some(new TransformContext)){
      ((map get TransID(-2)): @unchecked) match {
        case Some(p) => ((p get CircuitName(circuit.main)): @unchecked) match {
          case Some(ReplSeqMemAnnotation(t, _)) =>
            val conf = new java.io.File(PassConfigUtil.getPassOptions(t)(OutputConfigFileName))
            val seqMems = (MemConfReader(conf) map (m => m.name -> m)).toMap
            run(circuit, Seq(
              Analyses,
              new Fame1Transform(seqMems),
              new AddDaisyChains(seqMems),
              new DumpChains(seqMems)
            ))
        }
      }
    }
  }
}
