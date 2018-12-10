// See LICENSE for license details.

package midas.targetutils

import firrtl.annotations.{SingleTargetAnnotation, ComponentName, ReferenceTarget}

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


// This labels a target Mem so that it is extracted and replaced with a separate model
case class MemModelAnnotation[T <: chisel3.Data](target: chisel3.MemBase[T])
    extends chisel3.experimental.ChiselAnnotation {
  def toFirrtl = FirrtlMemModelAnnotation(target.toNamed.toTarget)
}

case class FirrtlMemModelAnnotation(target: ReferenceTarget) extends
    SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(rt: ReferenceTarget) = this.copy(target = rt)
}
