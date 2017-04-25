package strober
package replay

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import java.io.{File, FileWriter, Writer}

object SeqMemPathAnnotation {
  def apply(t: String, conf: File) =
    Annotation(CircuitName(t), classOf[SeqMemPathAnalysis], conf.toString)
  def unapply(a: Annotation) = a match {
    case Annotation(CircuitName(t), transform, conf) if transform == classOf[SeqMemPathAnalysis] =>
      Some(CircuitName(t), new File(conf))
    case _ => None
  }
}

private[replay] class SeqMemPathAnalysis(dir: File) extends Transform {
  def inputForm = MidForm
  def outputForm = MidForm
  def execute(state: CircuitState) = (getMyAnnotations(state): @unchecked) match {
    case Seq(SeqMemPathAnnotation(CircuitName(state.circuit.main), conf)) =>
      val file = new FileWriter(new File(dir, s"${state.circuit.main}.macro.paths"))
      val insts = new firrtl.analyses.InstanceGraph(state.circuit)
      val paths = midas.passes.MemConfReader(conf) foreach { m =>
        (insts findInstancesInHierarchy m.name) foreach { is =>
          val path = is map (_.name) mkString "."
          file write s"${m.name} $path\n"
        }
      }
      file.close
      state
  }
}
