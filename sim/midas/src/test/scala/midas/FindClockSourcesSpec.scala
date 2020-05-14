
package goldengate.tests

import midas.passes._

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.stage.transforms.Compiler
import firrtl.stage.Forms
import firrtl.options.Dependency
import firrtl.testutils._


class FindClockSourceSpec extends LowTransformSpec  {
   def transform = FindClockSources

   def executeAnnosOnly(input: String, annotations: Seq[Annotation], checkAnnotations: Seq[Annotation]): CircuitState = {
      val finalState = new Compiler(Forms.LowForm :+ Dependency(transform)).execute(CircuitState(parse(input), ChirrtlForm, annotations))

      annotations.foreach { anno =>
        logger.debug(anno.serialize)
      }

      finalState.annotations.toSeq.foreach { anno =>
        logger.debug(anno.serialize)
      }

      val csAnnos =  finalState.annotations.collect { case a: ClockSourceAnnotation => a  }
      checkAnnotations.foreach { check => csAnnos should contain (check) }
      finalState
   }

   "FindClockSources" should s"find sources for submodule clocks" in {
      val input =
         """circuit Top :
           |  module Top :
           |    input clock : Clock
           |    input clock2 : Clock
           |    inst a1 of A
           |    a1.clock <= clock
           |  module A :
           |    input clock : Clock
           """.stripMargin
      val aClockRT = ModuleTarget("Top", "A").addHierarchy("Top", "a1").ref("clock")
      val topClockRT = ModuleTarget("Top", "Top").ref("clock")
      val annos = Seq(FindClockSourceAnnotation(aClockRT))
      val checkAnnos = Seq(ClockSourceAnnotation(aClockRT, Some(topClockRT)))
      executeAnnosOnly(input, annos, checkAnnos)
    }

   it should s"find sources that pass through other modules" in {
      val input =
         """circuit Top :
           |  module Top :
           |    input clock : Clock
           |    input clock2 : Clock
           |    inst a1 of A
           |    inst p of PassThru
           |    p.iclock <= clock
           |    a1.clock <= p.oclock
           |  module A :
           |    input clock : Clock
           |  module PassThru :
           |    input iclock : Clock
           |    output oclock : Clock
           |    oclock <= iclock
           """.stripMargin
      val aClockRT = ModuleTarget("Top", "A").addHierarchy("Top", "a1").ref("clock")
      val topClockRT = ModuleTarget("Top", "Top").ref("clock")
      val annos = Seq(FindClockSourceAnnotation(aClockRT))
      val checkAnnos = Seq(ClockSourceAnnotation(aClockRT, Some(topClockRT)))
      executeAnnosOnly(input, annos, checkAnnos)
    }

   it should s"find sources for clocks at the root of the module hiearchy" in {
      val input =
         """circuit Top :
           |  module Top :
           |    input clock : Clock
           |    input clock2 : Clock
           |    wire c : Clock
           |    c <= clock2
           """.stripMargin
      val topClockRT = ModuleTarget("Top", "Top").ref("clock2")
      val wireClockRT = ModuleTarget("Top", "Top").ref("c")
      val annos = Seq(FindClockSourceAnnotation(wireClockRT))
      val checkAnnos = Seq(ClockSourceAnnotation(wireClockRT, Some(topClockRT)))
      executeAnnosOnly(input, annos, checkAnnos)
   }

   it should s"find sources for intermediate nodes in a chain of clock connections" in {
      val input =
         """circuit Top :
           |  module Top :
           |    input clock : Clock
           |    input clock2 : Clock
           |    wire c : Clock
           |    wire d : Clock
           |    c <= clock2
           |    d <= c
           """.stripMargin
      val topClockRT = ModuleTarget("Top", "Top").ref("clock2")
      val cClockRT = ModuleTarget("Top", "Top").ref("c")
      val dClockRT = ModuleTarget("Top", "Top").ref("d")
      val annos = Seq(FindClockSourceAnnotation(cClockRT),
                      FindClockSourceAnnotation(dClockRT))
      val checkAnnos = Seq(ClockSourceAnnotation(cClockRT, Some(topClockRT)),
                           ClockSourceAnnotation(dClockRT, Some(topClockRT)))
      executeAnnosOnly(input, annos, checkAnnos)
   }

}
