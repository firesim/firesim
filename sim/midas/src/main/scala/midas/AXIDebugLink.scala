// See LICENSE for license details.

package midas

import firrtl.annotations.{ReferenceTarget, SingleTargetAnnotation}
import firrtl.transforms.DontTouchAllTargets

/** Carries the optional platform debug transport to AutoILA after lowering. */
case class AXIDebugLinkAnnotation(target: ReferenceTarget, port: String, input: Boolean, width: Int)
    extends SingleTargetAnnotation[ReferenceTarget]
    with DontTouchAllTargets {
  def duplicate(n: ReferenceTarget) = copy(target = n)
}
