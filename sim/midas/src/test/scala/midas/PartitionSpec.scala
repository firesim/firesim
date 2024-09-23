package goldengate.tests

import chisel3._
import chisel3.experimental.DataMirror
import chisel3.stage.{ChiselCircuitAnnotation, ChiselGeneratorAnnotation, ChiselStage, CircuitSerializationAnnotation}

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.TestSuite

import firrtl._
import firrtl.annotations.{DeletedAnnotation, JsonProtocol}
import firrtl.options.TargetDirAnnotation
import firrtl.stage.{FirrtlCircuitAnnotation, RunFirrtlTransformAnnotation}
import firrtl.transforms.DontTouchAnnotation

import firesim.lib.bridgeutils.BridgeAnnotation
import midas.targetutils.{FirrtlEnableModelMultiThreadingAnnotation, FirrtlMemModelAnnotation}
import midas.passes.partition.PrintAllPass

import org.chipsalliance.cde.config.Config

import java.io.{File, PrintWriter}
import midas.targetutils.RoCCBusyFirrtlAnnotation

trait GoldenGateCompilerTest { this: TestSuite =>
  protected def annos: AnnotationSeq = Seq()

  protected def compile[M <: RawModule](
    gen:    => M,
    target: String,
    a:      AnnotationSeq = List(),
    ll:     String        = "warn",
  ): (String, AnnotationSeq) = {
    val stage = new ChiselStage

    val testName   = this.suiteName
    val testRunDir = TargetDirAnnotation("test_run_dir" + File.separator + testName)

    val r   =
      stage.execute(Array("-X", target, "-ll", ll), ChiselGeneratorAnnotation(() => gen) +: testRunDir +: a ++: annos)
    val src = r
      .collect {
        case EmittedFirrtlCircuitAnnotation(a)  => a
        case EmittedFirrtlModuleAnnotation(a)   => a
        case EmittedVerilogCircuitAnnotation(a) => a
        case EmittedVerilogModuleAnnotation(a)  => a
      }
      .map(_.value)
      .mkString("")
    (src, r)
  }

  protected def writeFile(dir: String, file: String, contents: String): Unit = {
    val d  = new File(dir)
    d.mkdirs()
    val f  = new File(dir + "/" + file)
    val pw = new PrintWriter(f)
    pw.write(contents)
    pw.close()
  }
}

class FireSimFirrtlAndAnnotationGenerator extends AnyFreeSpec with GoldenGateCompilerTest {
  def generateFireSimFirrtlAndAnnotations(cfg: Config) = {
    val (firrtl, annos) = compile(new FireSim()(cfg), "low", a = Seq(RunFirrtlTransformAnnotation(new PrintAllPass)))

    writeFile("midas/generated-src", "firesim.fir", firrtl)

    val annosWithoutFAME5ANnos = annos.filter(a =>
      a match {
        case _: FirrtlEnableModelMultiThreadingAnnotation => false
        case _: FirrtlMemModelAnnotation                  => false
        case _                                            => true
      }
    )

    val FAME5PathAnnos = annos.filter(a =>
      a match {
        case _: FirrtlEnableModelMultiThreadingAnnotation => true
        case _                                            => false
      }
    )

    val FAME5Annos =
      if (FAME5PathAnnos.size == 1) Seq()
      else
        FAME5PathAnnos.map { a =>
          val fa    = a.asInstanceOf[FirrtlEnableModelMultiThreadingAnnotation].targets.head
          val newfa = fa.copy(module = fa.path.last._2.value, path = Seq())
          FirrtlEnableModelMultiThreadingAnnotation(newfa)
        }

    val FAME5MemPathAnnos = annos.filter(a =>
      a match {
        case _: FirrtlMemModelAnnotation => true
        case _                           => false
      }
    )

    val FAME5MemAnnos =
      if (FAME5MemPathAnnos.size == 1) Seq()
      else
        FAME5MemPathAnnos.map { a =>
          val fa    = a.asInstanceOf[FirrtlMemModelAnnotation].target
          val newfa = fa.copy(module = fa.path.last._2.value, path = Seq())
          FirrtlMemModelAnnotation(newfa)
        }

    val newAnnos = annosWithoutFAME5ANnos ++ FAME5Annos ++ FAME5MemAnnos

    val annoWriter = new PrintWriter(new File("midas/generated-src/firesim.anno.json"))
    annoWriter.write(JsonProtocol.serialize(newAnnos.filter(_ match {
      case _: DeletedAnnotation                         => false
      case _: EmittedComponent                          => false
      case _: EmittedAnnotation[_]                      => false
      case _: FirrtlCircuitAnnotation                   => false
      case _: ChiselCircuitAnnotation                   => false
      case _: CircuitSerializationAnnotation            => false
      case _: BridgeAnnotation                          => true
      case _: DontTouchAnnotation                       => true
      case _: FirrtlEnableModelMultiThreadingAnnotation => true
      case _: FirrtlMemModelAnnotation                  => true
      case _: RoCCBusyFirrtlAnnotation                  => // TODO : Add serializer to use this...?
        false
      case _                                            => false
    })))
    annoWriter.close()
  }
}
