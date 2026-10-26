// See LICENSE for license details.

package midas

import chisel3._
import chisel3.reflect.DataMirror
import org.chipsalliance.cde.config.Field

/** Platform option: stabilize model payloads on the host falling edge while preserving the upstream ready/valid,
  * clock-enable and timing-model logic.
  */
case object HostPayloadHoldEnabled extends Field[Boolean](false)

object HostPayloadHold {
  def falling(clock: Clock): Clock = (!clock.asUInt.asBool).asClock

  /** Mirror model inputs through falling-edge registers; outputs pass through. Record/Vec traversal avoids
    * timing-model-specific generated RTL names.
    */
  def proxy[T <: Data](model: T, hostClock: Clock): T = {
    val mirror = Wire(chiselTypeOf(model))
    def connect(actual: Data, external: Data, path: String): Unit = (actual, external) match {
      case (a: Record, b: Record) =>
        a.elements.foreach { case (name, value) => connect(value, b.elements(name), path + "_" + name) }
      case (a: Vec[_], b: Vec[_]) => a.zip(b).zipWithIndex.foreach { case ((x, y), i) => connect(x, y, path + "_" + i) }
      case _                      =>
        DataMirror.directionOf(actual) match {
          case ActualDirection.Input  =>
            val held = withClock(falling(hostClock)) { RegNext(external) }
            held.suggestName("host_hold" + path); dontTouch(held)
            actual := held
          case ActualDirection.Output => external := actual
          case other                  => throw new IllegalArgumentException(s"Unsupported model payload direction $other at $path")
        }
    }
    connect(model, mirror, "")
    mirror
  }
}

/** Optional host timing treatment for internal cross-clock triggers. */
case object HostTriggerSnapshotEnabled extends Field[Boolean](false)

case class HostTriggerSnapshotAnnotation(target: firrtl.annotations.ReferenceTarget)
    extends firrtl.annotations.SingleTargetAnnotation[firrtl.annotations.ReferenceTarget]
    with firrtl.transforms.DontTouchAllTargets {
  def duplicate(n: firrtl.annotations.ReferenceTarget) = copy(target = n)
}

/** Qualification option: isolate host output timing from multiclock target logic. */
case object HostTargetOutputIsolationEnabled extends Field[Boolean](false)
