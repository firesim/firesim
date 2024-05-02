package midas.passes.partition

import scala.annotation.tailrec

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import firrtl.passes.{ResolveKinds, InferTypes}

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
    val mid2low = new MiddleFirrtlToLowFirrtl
    val high2mid = new HighFirrtlToMiddleFirrtl
    val lowered = mid2low.execute(high2mid.execute(state))
    lowered
  }
}

trait PromoteSubmodulesByNamePass {
  import firrtl.analyses.InstanceKeyGraph

  @tailrec
  final def promoteModules(state: CircuitState, module: String): CircuitState = {
    val igraph = InstanceKeyGraph(state.circuit)
    val instPaths = igraph.findInstancesInHierarchy(module).map {
      p => p.map(_.toTokens)
    }
    val instTargets = instPaths.flatMap { ip =>
      if (ip.size > 2) {
        val (i, m) = ip.last
        val (i1, m1) = ip.dropRight(1).last
        Some(InstanceTarget(state.circuit.main, m1.value, Nil, i.value, m1.value))
      } else {
        None
      }
    }

    if (instTargets.size > 0) {
      val anns = instTargets.map(it => PromoteSubmoduleAnnotation(it)) ++ state.annotations
      val nextPromoted = (new PromoteSubmodule).runTransform(state.copy(annotations = anns))
      promoteModules(nextPromoted, module)
    } else {
      state
    }
  }
}

trait GetNumInstancesPerFPGA {
  import firrtl.analyses.InstanceKeyGraph

  def getNumInstancesPerFPGA(state: CircuitState, modules: Seq[String], totalFPGAs: Int): Int = {
    modules.foreach(m => println(s"Module to remove/extract ${m}"))

    val igraph = InstanceKeyGraph(state.circuit)

    val fpgaCountExceptPartitionBase = totalFPGAs - 1

    val instanceCnt = modules.map { mName => 
      val ikeys = igraph.findInstancesInHierarchy(mName)
      ikeys.size
    }.reduce(_ + _)

    println(s"instanceCnt ${instanceCnt} fpgaCountExceptPartitionBase ${fpgaCountExceptPartitionBase}")

    val groupSize = instanceCnt / fpgaCountExceptPartitionBase

    assert(totalFPGAs > 1, s"Requires at least 2 FPGAs to perform partitioning, got ${totalFPGAs} FPGAs")
    assert(instanceCnt % fpgaCountExceptPartitionBase == 0, "instanceCount ${instanceCount} must be a multiple of ${fpgaCountExceptPartitionBase}")
    assert(instanceCnt > 0, s"Could not find a instance of the module to partition, check if your module names are correct!")
    assert(groupSize > 0, s"groupSize should be larger than zero ${groupSize}")

    groupSize
  }
}


trait ModuleNameParser {
  def parseModuleNames(name: String): Seq[String] = {
    val moduleWithRanges = name.split("\\+")
    val moduleNames = moduleWithRanges.flatMap { mrng =>
      val modAndRange = mrng.split("\\.")
      val module = modAndRange.head
      val ranges = modAndRange.drop(1)

      val idxSfx = if (ranges.size == 0) {
        Array("")
      } else {
        val idxs = ranges.flatMap { rng =>
          val startEnd = rng.split("\\~")
          if (startEnd.size == 1) startEnd.toSeq
          else (startEnd(0).toInt to startEnd(1).toInt).map(_.toString)
        }
        val sfx = idxs.map { i =>
          if (i.toInt == 0) ""
          else s"_${i}"
        }
        sfx
      }
      idxSfx.map ( i => module + i )
    }
    moduleNames.toSeq
  }
}
