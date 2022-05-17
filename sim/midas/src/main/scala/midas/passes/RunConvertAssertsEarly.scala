// See LICENSE for license details.

package midas.passes

import firrtl.{CircuitState, DependencyAPIMigration, Transform}
import firrtl.transforms.formal.ConvertAsserts
import firrtl.passes.RemoveCHIRRTL
import firrtl.options.Dependency

/**
  * Ensure ConvertAsserts is run as early as possible
  *
  * This transform does nothing but inject a dependency such that
  * ConvertAsserts runs as early in the compiler as possible (before
  * RemoveCHIRRTL). This permits new assertion nodes to be lowered into the
  * old form such they can be detected by AssertionSynthesis. There is
  * currently no simple mechanism to re-associate an assert-printf pair,
  * without introducing new annotations.
  */

class RunConvertAssertsEarly extends Transform with DependencyAPIMigration {
  override def prerequisites          = Nil
  override def optionalPrerequisites  = Seq(Dependency(ConvertAsserts))
  override def optionalPrerequisiteOf = Seq(Dependency(RemoveCHIRRTL))

  override def invalidates(a: Transform): Boolean         = false
  override def execute(state: CircuitState): CircuitState = state
}
