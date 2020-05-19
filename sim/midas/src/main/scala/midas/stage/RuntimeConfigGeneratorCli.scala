// See LICENSE for license details.

package midas.stage

import firrtl.options.Shell

trait RuntimeConfigGeneratorCli { this: Shell =>
  parser.note("Golden Gate Runtime Configuration Generator Options")
  Seq(ConfigPackageAnnotation,
      ConfigStringAnnotation,
      RuntimeConfigNameAnnotation
    )
    .map(_.addOptions(parser))
}
