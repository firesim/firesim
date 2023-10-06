// See LICENSE for license details.
// Based on Rocket Chip's stage implementation

package midas.chiselstage.stage

import chisel3.stage.{ChiselCli, ChiselStage}
import firrtl.options.PhaseManager.PhaseDependency
import firrtl.stage.FirrtlCli
import firrtl.options.{Dependency, Phase, Shell}

final class MidasChiselStage extends ChiselStage {
  override val targets = Seq(
    Dependency[chisel3.stage.phases.Checks],
    Dependency[chisel3.stage.phases.Elaborate],
    Dependency[chisel3.stage.phases.AddImplicitOutputFile],
    Dependency[chisel3.stage.phases.AddImplicitOutputAnnotationFile],
    Dependency[chisel3.stage.phases.MaybeAspectPhase],
    Dependency[chisel3.stage.phases.Emitter],
    Dependency[chisel3.stage.phases.Convert],
  )
}

class MidasStage extends ChiselStage {
  override val shell                         = new Shell("midas") with MidasCli with ChiselCli with FirrtlCli
  override val targets: Seq[PhaseDependency] = Seq(
    Dependency[midas.chiselstage.stage.phases.Checks],
    Dependency[midas.chiselstage.stage.phases.TransformAnnotations],
    Dependency[midas.chiselstage.stage.phases.PreElaboration],
    Dependency[MidasChiselStage],
    Dependency[midas.chiselstage.stage.phases.GenerateFirrtlAnnos],
    Dependency[midas.chiselstage.stage.phases.GenerateArtefacts],
  )
  override final def invalidates(a: Phase): Boolean = false
}
