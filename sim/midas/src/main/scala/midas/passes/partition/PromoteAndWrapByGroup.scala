package midas.passes.partition

import Array.range
import scala.Console.println
import scala.collection.mutable

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.ir.EmptyStmt
import firrtl.analyses.InstanceKeyGraph
import firrtl.passes.{ResolveKinds, InferTypes}

import midas.passes.RemoveTrivialPartialConnects


trait PromoteAndWrapByGroupPass 
    extends InsertWrapperPass
    with GroupAndInsertWrapperPass
    with PromoteSubmodulesByNamePass
    with StateToLowFIRRTLPass
    with DedupFAME5InstancesPass
{
  def promoteAndWrapByGroup(
      state: CircuitState,
      moduleNames: Seq[String],
      wrapperModuleNames: Seq[String],
      groupWrapperPfx: String,
      groupSize: Int): CircuitState = {

    println("- Promote the modules to extract to top level")
    val promotedState = moduleNames.foldLeft(state) { case (st, module) =>
      promoteModules(st, module)
    }

    println("- Lower to LowFIRRTL")
    val loweredState = toLowFirrtl(removePartial(promotedState))

    println(s"- Wrap the individual modules to extract")
    val modulesWrappedState = moduleNames.zip(wrapperModuleNames).foldLeft(loweredState)(
      (st, mmw) => wrapModule(st, mmw._1, mmw._2)
    )

    println(s"- Wrap the modules to extract by groups of ${groupSize}")
    val groupWrappedState = wrapModulesByGroups(
        modulesWrappedState,
        wrapperModuleNames.toSet,
        groupSize,
        groupWrapperPfx)

    println(s"- Deduplicate instances to multithread")
    deduplicateInstancesOnFAME5(groupWrappedState)
  }
}
