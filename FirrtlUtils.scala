
package midas

import firrtl._
import firrtl.Utils._
import firrtl.passes._
import java.io.Writer

// Utilities that should probably be in FIRRTL

object LoFirrtlCompiler extends firrtl.Compiler {
  val passes = Seq(
    CInferTypes,
    CInferMDir,
    RemoveCHIRRTL,
    ToWorkingIR,            
    CheckHighForm,
    ResolveKinds,
    InferTypes,
    CheckTypes,
    ResolveGenders,
    CheckGenders,
    InferWidths,
    CheckWidths,
    PullMuxes,
    ExpandConnects,
    RemoveAccesses,
    ExpandWhens,
    CheckInitialization,   
    Legalize,
    ConstProp,
    ResolveKinds,
    InferTypes,
    ResolveGenders,
    InferWidths,
    LowerTypes
  )
  def run(c: Circuit, w: Writer)
  {
    val loweredIR = PassUtils.executePasses(c, passes)
    w.write(loweredIR.serialize)
  }
}
