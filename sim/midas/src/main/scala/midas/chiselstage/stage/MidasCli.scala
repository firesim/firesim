// See LICENSE for license details.
// Based on Rocket Chip's stage implementation

package midas.chiselstage.stage

import firrtl.options.Shell

trait MidasCli { this: Shell =>

  parser.note("Midas Generator Options")
  Seq(
    TopModuleAnnotation,
    ConfigsAnnotation,
    OutputBaseNameAnnotation,
    UnderscoreDelimitedConfigsAnnotation,
  ).foreach(_.addOptions(parser))
}
