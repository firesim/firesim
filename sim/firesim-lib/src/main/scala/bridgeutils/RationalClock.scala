// See LICENSE for license details.

package firesim.lib.bridgeutils

/** Defines a generated clock as a rational multiple of some reference clock. The generated clock has a frequency
  * (multiplier / divisor) times that of reference.
  *
  * @param name
  *   An identifier for the associated clock domain
  *
  * @param multiplier
  *   See class comment.
  *
  * @param divisor
  *   See class comment.
  */
case class RationalClock(name: String, multiplier: Int, divisor: Int) {
  def simplify: RationalClock = {
    val gcd = BigInt(multiplier).gcd(BigInt(divisor)).intValue
    RationalClock(name, multiplier / gcd, divisor / gcd)
  }

  def equalFrequency(that: RationalClock): Boolean =
    this.simplify.multiplier == that.simplify.multiplier &&
      this.simplify.divisor == that.simplify.divisor

  def toC(): String = {
    s"ClockInfo{${CStrLit(name).toC}, ${UInt32(multiplier).toC}, ${UInt32(divisor).toC}}"
  }
}
