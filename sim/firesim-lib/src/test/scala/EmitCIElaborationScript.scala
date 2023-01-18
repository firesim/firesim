//See LICENSE for license details.
package firesim

import java.io.File
import java.io.FileWriter

/**
  * Instantiates a TestSuite and pulls out all of the required make calls to
  * elaborate all tests.
  *
  * 1st argument: name of the output script
  * 2nd argument: fully qualified suite name
  */
object EmitCIElaborationScript extends App {
  assert(args.size == 2)
  val Array(scriptName, className) = args
  val inst = Class.forName(className).getConstructors().head.newInstance()

  def recurse(suite: Any): Seq[String] = suite match {
    case t: TestSuiteCommon => Seq(t.makeCommand(t.elaborateMakeTarget:_*).mkString(" "))
    case t: org.scalatest.Suites => t.nestedSuites.flatMap(recurse)
  }

  val makeCommands = recurse(inst)
  val writer = new FileWriter(new File(scriptName))
  makeCommands.foreach { l =>  writer.write(l + "\n") }
  writer.close
}
