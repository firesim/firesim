package midas.chiselstage

import firrtl.options.StageMain

object Generator extends StageMain(new midas.chiselstage.stage.MidasStage)
