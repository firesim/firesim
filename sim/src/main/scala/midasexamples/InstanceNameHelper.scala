//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.experimental.BaseModule

/**
  * This enables using the full path to a module before it's name is reflexively
  * defined. It works by suggesting names that should be stable, these strings
  * can then be used in verification collateral that is created during
  * elaboration.
  *
  * Sharp edge: the top module technically does not have an "instance name". Might
  * need to permit returning an empty Seq at the top of the module hierarchy instead
  * of throwing an exception if generalizing beyond AutoCounter.
  *
  */
trait InstanceNameHelper {
  private var _instPathStack = List[(BaseModule, String)]()

  // This grows the stack, and should be called on module entry
  def setModuleName(suggestedName: String): Unit = {
    val currentModule = Module.currentModule.get
    currentModule.suggestName(suggestedName)
    _instPathStack = (currentModule -> suggestedName) +: _instPathStack
  }

  // Returns the current instPath, shrinking the stack until it reaches the
  // current context.
  def currentInstPath(): Seq[String] = _instPathStack match {
    case Nil => throw new RuntimeException(
      s"Could not resolve instance path for Module ${Module.currentModule.get}. Did you forget to call setModuleName?")
    case (mod, _) :: pairs if mod != Module.currentModule.get =>
      _instPathStack = pairs
      currentInstPath()
    case pairs =>
      pairs.map(_._2)
  }
}
