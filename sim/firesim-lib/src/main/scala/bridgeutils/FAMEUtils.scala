// See LICENSE for license details.

package firesim.lib.bridgeutils

import firrtl.RenameMap
import firrtl.annotations.ReferenceTarget

object RTRenamer {
  // TODO: determine order for multiple renames, or just check of == 1 rename?
  def exact(renames: RenameMap): (ReferenceTarget => ReferenceTarget) = {
    { rt =>
      val renameMatches = renames.get(rt).getOrElse(Seq(rt)).collect({ case rt: ReferenceTarget => rt })
      assert(
        renameMatches.length == 1,
        s"renameMatches for ${rt} is ${renameMatches.length}, not 1. Matches:" + renameMatches.mkString("\n"),
      )
      renameMatches.head
    }
  }

  def apply(renames: RenameMap): (ReferenceTarget => Seq[ReferenceTarget]) = {
    { rt => renames.get(rt).getOrElse(Seq(rt)).collect({ case rt: ReferenceTarget => rt }) }
  }
}
