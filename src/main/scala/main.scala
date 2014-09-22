package faee

import Chisel._
import TutorialExamples._

object FAEE {
  def main(args: Array[String]) {
    val chiselArgs = args.slice(1, args.length)
    val res = args(0) match {
      case "GCD" =>
        chiselMainTest(chiselArgs, () => DaisyWrapper(new GCD))(
          c => new GCDDaisyTests(c))
      case _ =>
    }
  }
}
