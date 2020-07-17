// See LICENSE for license details.
package firrtlTests.options


/**
  * Trying to run a Stage in these these tests leads to firrtl.options.Shell
  * complaining  there is no provider for these two classes (and justly so --
  * these are included in firrtlTests.options). Provide spoofs of these
  * classes as a workaround until this is fixed properly.
  */

import firrtl.options.{RegisteredTransform, RegisteredLibrary, ShellOption}
import firrtl.passes.Pass
import firrtl.ir.Circuit

class FooTransform extends Pass with RegisteredTransform {
  def run(c: Circuit): Circuit = c
  val options = Seq()
}

class BarLibrary extends RegisteredLibrary {
  def name: String = "Bar"
  val options = Seq()
}

