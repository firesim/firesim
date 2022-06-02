//See LICENSE for license details.
package midas.passes

import firrtl.CircuitState
import firrtl.annotations.{ReferenceTarget, SingleTargetAnnotation}

/** Labels the host clock provided by to FPGATop by the PlatformShim. This marks the simulators clock for use by
  * host-side transformations such as AutoILA injection.
  * @param target
  *   The port on FPGATop through which the main simulator clock is driven.
  */
private[midas] case class HostClockSource(target: ReferenceTarget) extends SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(t: ReferenceTarget): HostClockSource = this.copy(t)
}

private[midas] object HostClockSource {
  def annotate(clock: chisel3.Clock): Unit = {
    chisel3.experimental.annotate(new chisel3.experimental.ChiselAnnotation {
      def toFirrtl = HostClockSource(clock.toTarget)
    })
  }
}

/** Labels sinks that must be driven by the HostClock.
  */
case class HostClockSink(target: ReferenceTarget) extends SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(t: ReferenceTarget): HostClockSink = this.copy(t)
}

/** Does final host clock wiring for transform-injected hardware.
  */
object HostClockWiring extends AnnotationParameterizedWiringTransform[HostClockSource, HostClockSink](true, false) {

  /** Sugar to let passes invoke HostClockWiring internally. It leaves the HostClockSource in place for future
    * invocations.
    *
    * @param state
    *   The input circuit to transform
    */
  def apply(state: CircuitState): CircuitState = {
    val sourceAnnos = state.annotations.collect { case a: HostClockSource => a }
    val newState    = execute(state)
    newState.copy(annotations = sourceAnnos ++ newState.annotations)
  }
}
