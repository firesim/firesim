package strober
package replay

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Annotations._
import midas.passes._
import midas.passes.MidasTransforms._
import java.io.{File, FileWriter, Writer}

private[replay] class SeqMemPathAnalysis(
    dir: File,
    childMods: ChildMods,
    childInsts: ChildInsts,
    instModMap: InstModMap,
    seqMems: Map[String, MemConf]) extends firrtl.passes.Pass {
  def name = "[strober] SeqMem Path Analysis"

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

private[replay] case class StroberAnnotation(t: String, conf: File)
    extends Annotation with Loose with Unstable {
  val target = CircuitName(t)
  def duplicate(n: Named) = this.copy(t=n.name)
  def transform = classOf[StroberAnalyses]
}

private[replay] class StroberAnalyses(dir: File) extends Transform with SimpleRun {
  val childMods = new ChildMods
  val childInsts = new ChildInsts
  val instModMap = new InstModMap

  def inputForm = MidForm
  def outputForm = MidForm
  def execute(state: CircuitState) = (getMyAnnotations(state): @unchecked) match {
    case Seq(StroberAnnotation(t, conf)) if t == state.circuit.main =>
      val seqMems = (MemConfReader(conf) map (m => m.name -> m)).toMap
      CircuitState(runPasses(state.circuit, Seq(
        new TransformAnalysis(childMods, childInsts, instModMap),
        new SeqMemPathAnalysis(dir, childMods, childInsts, instModMap, seqMems)
      )), outputForm)
  }
}
