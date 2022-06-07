//See LICENSE for license details.
package firesim.util

import java.io.{File, FileWriter}

import freechips.rocketchip.config.{Field, Config}

object BuildStrategy extends Field[BuildStrategies.IsBuildStrategy](BuildStrategies.Timing)

class WithILADepth(depth: Int) extends Config((site, here, up) => {
    case midas.ILADepthKey => depth
})

object BuildStrategies {
  trait IsBuildStrategy {
    def flowString: String
    def emitTcl =  "set strategy \"" + flowString + "\"\n"
  }
  object Basic extends IsBuildStrategy { val flowString = "BASIC" }
  // Tries to minimize resource utilization. Notably passes directive
  // "AreaOptimized_high" to synth_design.
  object Area extends IsBuildStrategy { val flowString = "AREA" }
  // This is the default strategy AWS sets in "aws_build_dcp_from_cl.sh"
  object Timing extends IsBuildStrategy { val flowString = "TIMING" }
  object Explore extends IsBuildStrategy { val flowString = "EXPLORE" }
  object Congestion extends IsBuildStrategy { val flowString = "CONGESTION" }
  // This is the same as the Timing strategy, except it removes the -retiming flag to avoid Vivado crashes
  // during explicit retiming
  object NoRetiming extends IsBuildStrategy { val flowString = "NORETIMING" }
  // This is the strategy AWS uses if you give it a bogus strategy string
  object Default extends IsBuildStrategy { val flowString = "DEFAULT" }
}

// Overrides the AWS default strategy with a desired one
class WithBuildStrategy(strategy: BuildStrategies.IsBuildStrategy) extends Config((site, here, up) => {
  case BuildStrategy => strategy
})

class  ILADepth1024 extends WithILADepth(1024)
class  ILADepth2048 extends WithILADepth(2048)
class  ILADepth4096 extends WithILADepth(4096)
class  ILADepth8192 extends WithILADepth(8192)
class ILADepth16384 extends WithILADepth(16384)



class Congestion extends WithBuildStrategy(BuildStrategies.Congestion)
class Area extends WithBuildStrategy(BuildStrategies.Area)
class Timing extends WithBuildStrategy(BuildStrategies.Timing)
class Explore extends WithBuildStrategy(BuildStrategies.Explore)
class NoRetiming extends WithBuildStrategy(BuildStrategies.NoRetiming)
