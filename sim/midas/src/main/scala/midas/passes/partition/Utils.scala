package midas.passes.partition

import scala.annotation.tailrec
import firrtl._
import firrtl.annotations._
import firrtl.passes.{InferTypes, ResolveKinds}
import midas.passes.RemoveTrivialPartialConnects
import midas.passes.fame.{PromoteSubmodule, PromoteSubmoduleAnnotation}

trait StateToLowFIRRTLPass {
  // Transform partial connections to normal connections
  protected def removePartial(state: CircuitState): CircuitState = {
    val cx = RemoveTrivialPartialConnects.run(InferTypes.run(ResolveKinds.run(state.circuit)))
    state.copy(circuit = cx)
  }

  // Flatten all the bundles
  protected def toLowFirrtl(state: CircuitState): CircuitState = {
    val mid2low  = new MiddleFirrtlToLowFirrtl
    val high2mid = new HighFirrtlToMiddleFirrtl
    val lowered  = mid2low.execute(high2mid.execute(state))
    lowered
  }
}

trait PromoteSubmodulesByNamePass {
  import firrtl.analyses.InstanceKeyGraph

  @tailrec
  final def promoteModules(state: CircuitState, module: String): CircuitState = {
    val igraph      = InstanceKeyGraph(state.circuit)
    val instPaths   = igraph.findInstancesInHierarchy(module).map { p =>
      p.map(_.toTokens)
    }
    val instTargets = instPaths.flatMap { ip =>
      if (ip.size > 2) {
        val (i, m)   = ip.last
        val (i1, m1) = ip.dropRight(1).last
        Some(InstanceTarget(state.circuit.main, m1.value, Nil, i.value, m1.value))
      } else {
        None
      }
    }

    if (instTargets.size > 0) {
      val anns         = instTargets.map(it => PromoteSubmoduleAnnotation(it)) ++ state.annotations
      val nextPromoted = (new PromoteSubmodule).runTransform(state.copy(annotations = anns))
      promoteModules(nextPromoted, module)
    } else {
      state
    }
  }
}
