package strober
package passes

import firrtl._
import firrtl.passes._
import firrtl.Annotations._
import scala.collection.mutable.{HashMap, LinkedHashSet, ArrayBuffer}
import scala.util.DynamicVariable

private class TransformContext {
  type ChainMap = HashMap[String, ArrayBuffer[ir.Statement]]
  val childInsts = HashMap[String, ArrayBuffer[String]]()
  val childMods = HashMap[String, LinkedHashSet[String]]()
  val instToMod = HashMap[(String, String), String]()
  val chains = (ChainType.values.toList map (_ -> new ChainMap)).toMap
}

case class Fame1Annotation(t: String, conf: java.io.File)
    extends Annotation with Loose with Unstable {
  val target = CircuitName(t)
  val tID = TransID(1)
  def duplicate(n: Named) = this.copy(t=n.name)
}

case class DaisyAnnotation(t: String, conf: java.io.File)
    extends Annotation with Loose with Unstable {
  val target = CircuitName(t)
  val tID = TransID(2)
  def duplicate(n: Named) = this.copy(t=n.name)
}

private[strober] object StroberTransforms extends Transform with SimpleRun {
  private val contextVar = new DynamicVariable[Option[TransformContext]](None)
  private[passes] def context = contextVar.value.getOrElse (new TransformContext)
  def execute(circuit: ir.Circuit, map: AnnotationMap) = {
    (contextVar withValue Some(new TransformContext)){
      val fame1 = ((map get TransID(1)): @unchecked) match {
        case Some(p) => ((p get CircuitName(circuit.main)): @unchecked) match {
          case Some(Fame1Annotation(_, conf)) =>
            run(circuit, Seq(
              Analyses,
              new Fame1Transform(conf)))
        }
      }
      val daisy = map get TransID(2) match {
        case None => fame1
        case Some(p) => ((p get CircuitName(circuit.main)): @unchecked) match {
          case Some(DaisyAnnotation(_, conf)) =>
            run(fame1.circuit, Seq(
              new AddDaisyChains(conf),
              new DumpChains(conf)))
        }
      }
      run(daisy.circuit, Seq(RemoveEmpty))
    }
  }
}
