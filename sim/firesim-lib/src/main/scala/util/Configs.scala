//See LICENSE for license details.
package firesim.util

import java.io.{File, FileWriter}

import freechips.rocketchip.config.{Field, Config}

object DesiredHostFrequency extends Field[Int](90) // Host FPGA frequency, in MHz
object ILADepth extends Field[Int](1024) // Depth of ILA traces
object BuildStrategy extends Field[BuildStrategies.IsBuildStrategy](BuildStrategies.Timing)

class WithDesiredHostFrequency(freq: Int) extends Config((site, here, up) => {
    case DesiredHostFrequency => freq
})

class WithILADepth(depth: Int) extends Config((site, here, up) => {
    case ILADepth => depth
})

object BuildStrategies {
  trait IsBuildStrategy {
    def flowString: String
    def emitTcl =  "set strategy \"" + flowString + "\"\n"
  }
  object Basic extends IsBuildStrategy { val flowString = "BASIC" }
  // This is the default strategy AWS sets in "aws_build_dcp_from_cl.sh"
  object Timing extends IsBuildStrategy { val flowString = "TIMING" }
  object Explore extends IsBuildStrategy { val flowString = "EXPLORE" }
  object Congestion extends IsBuildStrategy { val flowString = "CONGESTION" }
  // This is the strategy AWS uses if you give it a bogus strategy string
  object Default extends IsBuildStrategy { val flowString = "DEFAULT" }
}

// Overrides the AWS default strategy with a desired one
class WithBuildStategy(strategy: BuildStrategies.IsBuildStrategy) extends Config((site, here, up) => {
  case BuildStrategy => strategy
})

// Shortened names useful for appending to config strings in Make variables and build recipes
// example: BaseF1Config_F160MHz is equivalent to: new Config(new BaseF1Config ++ new F160MHz)
class F190MHz extends WithDesiredHostFrequency(190)
class F175MHz extends WithDesiredHostFrequency(175)
class F160MHz extends WithDesiredHostFrequency(160)
class F150MHz extends WithDesiredHostFrequency(150)
class F140MHz extends WithDesiredHostFrequency(140)
class F130MHz extends WithDesiredHostFrequency(130)
class F120MHz extends WithDesiredHostFrequency(120)
class F110MHz extends WithDesiredHostFrequency(110)
class F100MHz extends WithDesiredHostFrequency(100)
class  F90MHz extends WithDesiredHostFrequency(90)
class  F85MHz extends WithDesiredHostFrequency(85)
class  F80MHz extends WithDesiredHostFrequency(80)
class  F75MHz extends WithDesiredHostFrequency(75)
class  F70MHz extends WithDesiredHostFrequency(70)
class  F65MHz extends WithDesiredHostFrequency(65)
class  F60MHz extends WithDesiredHostFrequency(60)
class  F55MHz extends WithDesiredHostFrequency(55)
class  F50MHz extends WithDesiredHostFrequency(50)
class  F45MHz extends WithDesiredHostFrequency(45)
class  F40MHz extends WithDesiredHostFrequency(40)
class  F35MHz extends WithDesiredHostFrequency(35)
class  F30MHz extends WithDesiredHostFrequency(30)

class  ILADepth1024 extends WithILADepth(1024)
class  ILADepth2048 extends WithILADepth(2048)
class  ILADepth4096 extends WithILADepth(4096)
class  ILADepth8192 extends WithILADepth(8192)
class ILADepth16384 extends WithILADepth(16384)



class Congestion extends WithBuildStategy(BuildStrategies.Congestion)
