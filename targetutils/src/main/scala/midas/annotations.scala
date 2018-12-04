// See LICENSE for license details.

package midas.targetutils

import firrtl.annotations.{SingleTargetAnnotation, ComponentName}

// This is currently consumed by a transformation that runs after MIDAS's core
// transformations In FireSim, targeting an F1 host, these are consumed by the
// AutoILA infrastucture (ILATopWiring pass) to generate an ILA that plays nice
// with AWS's vivado flow
case class FpgaDebugAnnotation(target: chisel3.Data)
    extends chisel3.experimental.ChiselAnnotation {
  def toFirrtl = FirrtlFpgaDebugAnnotation(target.toNamed)
}

case class FirrtlFpgaDebugAnnotation(target: ComponentName) extends
    SingleTargetAnnotation[ComponentName] {
  def duplicate(n: ComponentName) = this.copy(target = n)
}
