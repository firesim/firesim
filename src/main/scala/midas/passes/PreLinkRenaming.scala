// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.annotations.{CircuitName, ModuleName}
import firrtl.ir._
import firrtl.Mappers._
import Utils._
import java.io.{File, FileWriter, StringWriter}

// This transform renames a circuit's modules and annotations in
// preparation for linking (eg. PlatformMapping). Subsumes Utils.renameMods
private[passes] class PreLinkRenaming(parentNamespace: Namespace) extends firrtl.Transform {

  override def name = "[MIDAS] Pre-link Module Renaming"
  // TODO: Technically this xform should be able to accept AnyForm(TM); and be form idempotent
  def inputForm = LowForm
  def outputForm = LowForm

  // Updates instantiations of modules that would alias
  def updateInsts(nameMap: Map[String, String])(s: Statement): Statement = s match {
    case s: WDefInstance if nameMap.contains(s.module) => s.copy(module = nameMap(s.module))
    case s => s.map(updateInsts(nameMap))
  }
  // Renames the module if there is a collision, and updates submodule inst names
  def updateModNames(nameMap: Map[String, String])(m: DefModule): DefModule = (m match {
      case m : ExtModule => m.copy(name = nameMap.getOrElse(m.name, m.name))
      case m :    Module => m.copy(name = nameMap.getOrElse(m.name, m.name))
  }) map updateInsts(nameMap)

  def execute(state: CircuitState): CircuitState = {
    val circuit = state.circuit
    require(!parentNamespace.contains(circuit.main), "Submodule in child has same name as top")

    // Find all modules that share a name with a module in the circuit we are linking against
    val modsToRename = circuit.modules.collect({
      case m if parentNamespace.contains(m.name) => m.name -> parentNamespace.newName(m.name)
    })

    // Generate a rename map to update annotations that reference de-aliased modules
    val cname = CircuitName(circuit.main)
    val renameMap = RenameMap(modsToRename.map({
      case (from, to) => ModuleName(from,cname) -> Seq(ModuleName(to,cname))
    }).toMap)
    renameMap.setCircuit(circuit.main)

    state.copy(circuit = circuit.copy(modules = circuit.modules map updateModNames(modsToRename.toMap)),
               renames = Some(renameMap))
  }
}
