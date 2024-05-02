package midas.passes.partition

import scala.Console.println
import scala.collection.mutable
import Array.range

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import firrtl.analyses.{InstanceKeyGraph, InstanceGraph}

import midas._
import midas.stage._
import midas.targetutils._

import org.chipsalliance.cde.config.{Parameters, Config}

class WrapAndGroupModulesToPartition
  extends Transform
  with DependencyAPIMigration 
  with ModuleNameParser
  with PromoteAndWrapByGroupPass
  with GetNumInstancesPerFPGA {

  import PartitionModulesInfo._

  def execute(state: CircuitState): CircuitState = {
    val p = getConfigParams(state.annotations)
    // If (Remove/Extract)ModuleNameAnnotation is found this is happening after the
    // NoCPartitionExtract pass. Otherwise, just use the FireAxePartitionInfo
    val partitionModuleNames = state.annotations.collectFirst(_ match {
      case RemoveModuleNameAnnotation(name) => name
      case ExtractModuleNameAnnotation(name) => name
    }).getOrElse(p(FireAxePartitionInfo))

    println(s"partitionModuleNames ${partitionModuleNames}")

    val partitionModules = parseModuleNames(partitionModuleNames)
    val partitionModuleWrappers = partitionModules.map(wrapperPfx + "_" + _)

    // I hate this code, it is too ugly
    val fpgaCount = state.annotations.collectFirst(_ match {
      case PartitionFPGACountAnnotation(x) => x
    }).getOrElse(2)
    val fpgaCountIfNoCPart = if (p(FireAxeNoCPartitionPass)) 2 else fpgaCount

    val groupSize = getNumInstancesPerFPGA(state, partitionModules, fpgaCountIfNoCPart)
    val groupedState = promoteAndWrapByGroup(
      state,
      partitionModules,
      partitionModuleWrappers, 
      groupPfx,
      groupSize)

    val (groups, groupWrappers) = getGroups(groupedState)
    val groupWrappedState = groups.zip(groupWrappers).foldLeft(groupedState) {
      (s, gw) => wrapModule(s, gw._1, gw._2)
    }
    groupWrappedState
  }
}


