package strober
package replay

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import midas.passes._
import midas.passes.MidasTransforms._
import java.io.{File, FileWriter, Writer}

private[replay] class SeqMemPathAnalysis(
    dir: File,
    childMods: ChildMods,
    childInsts: ChildInsts,
    instModMap: InstModMap,
    seqMems: Map[String, MemConf]) extends firrtl.passes.Pass {
  override def name = "[strober] SeqMem Path Analysis"

  private def findSeqMems(writer: Writer, path: String)(s: Statement): Statement = {
    s match {
      case s: WDefInstance => seqMems get s.module match {
        case Some(seqMem) =>
          writer write s"${s.module} $path.${s.name}\n"
        case None =>
      }
      case _ =>
    }
    s map findSeqMems(writer, path)
  }

  private def loop(writer: Writer,
                   path: String,
                   mod: DefModule,
                   mods: Seq[DefModule]) {
    mod map findSeqMems(writer, path)
    childInsts(mod.name) foreach { child =>
      val childMod = (mods find (m => m.name == instModMap(child -> mod.name))).get
      loop(writer, s"$path.$child", childMod, mods)
    }
  }

  def run(c: Circuit) = {
    val head = (c.modules find (_.name == c.main)).get
    val file = new FileWriter(new File(dir, s"${c.main}.macro.paths"))
    loop(file, c.main, head, c.modules)
    file.close
    c
  }
}

object StroberAnnotation {
  def apply(t: String, conf: File) =
    Annotation(CircuitName(t), classOf[StroberAnalyses], conf.toString)
  def unapply(a: Annotation) = a match {
    case Annotation(CircuitName(t), transform, conf) if transform == classOf[StroberAnalyses] =>
      Some(CircuitName(t), new File(conf))
    case _ => None
  }
}

private[replay] class StroberAnalyses(dir: File) extends Transform {
  val childMods = new ChildMods
  val childInsts = new ChildInsts
  val instModMap = new InstModMap

  def inputForm = MidForm
  def outputForm = MidForm
  def execute(state: CircuitState) = (getMyAnnotations(state): @unchecked) match {
    case Seq(StroberAnnotation(CircuitName(state.circuit.main), conf)) =>
      val seqMems = (MemConfReader(conf) map (m => m.name -> m)).toMap
      val transforms = Seq(
        new TransformAnalysis(childMods, childInsts, instModMap),
        new SeqMemPathAnalysis(dir, childMods, childInsts, instModMap, seqMems)
      )
      (transforms foldLeft state)((in, xform) => xform runTransform in) copy (form=outputForm)
  }
}
