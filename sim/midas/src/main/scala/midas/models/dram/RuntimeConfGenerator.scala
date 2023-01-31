package midas
package models


import chisel3._



// Hacky utilities to get console input from user.
trait HasConsoleUtils {
  def requestInput(query: String,
      default: BigInt,
      min: Option[BigInt] = None,
      max: Option[BigInt] = None): BigInt =  {
    def inner(): BigInt = {
      Console.printf(query + s"(${default}):")
      var value = default
      try  {
        val line = io.StdIn.readLine()
        if (line.length() > 0) {
          value = line.toInt
        }
        if (max != None && value > max.get) {
          Console.printf(s"Request integer ${value} exceeds maximum ${max.get}")
          inner()
        } else if (min != None && value < min.get) {
          Console.printf(s"Request integer ${value} is less than minimum ${min.get}")
          inner()
        }
      } catch {
        case e: java.lang.NumberFormatException => {
          Console.println("Please give me an integer!")
          value = inner()
        }
        case e: java.io.EOFException => { value = default }
      }
      value
    }
    inner()
  }

  // Select from list of possibilities
  // Format:
  //   HEADER
  //     POS 0
  //     ...
  //     POS N-1
  //   FOOTER (DEFAULT):
  def requestSeqSelection(
      header: String,
      possibilities: Seq[String],
      footer: String = "Selection number",
      default: BigInt = 0): Int = {

    val query = s"${header}\n" + (possibilities.zipWithIndex).foldRight(footer)((head, body) =>
      s"  ${head._2}) ${head._1}\n" + body)

    requestInput(query, default).toInt
  }

}

// Runtime settings are programmable registers that change behavior of a memory model instance
// These are instantatiated in the I/O of the timing model and tied to a Chisel Input
trait IsRuntimeSetting extends HasConsoleUtils {
  def default: BigInt
  def query: String
  def min: BigInt
  def max: Option[BigInt]

  private var _isSet = false
  private var _value: BigInt = 0

  def set(value: BigInt): Unit = {
    require(!_isSet, "Trying to set a programmable register that has already been set.")
    _value = value;
    _isSet = true
  }

  def isSet() = _isSet

  def getOrElse(alt: =>BigInt): BigInt = if (_isSet) _value else alt

  // This prompts the user via the console for setting
  def requestSetting(field: Data): Unit = {
    set(requestInput(query, default, Some(min), max))
  }
}

// A vanilla runtime setting of the memory model
case class RuntimeSetting(
  default: BigInt,
  query: String,
  min: BigInt = 0,
  max: Option[BigInt] = None) extends IsRuntimeSetting

// A setting whose value can be looked up from a provided table.
case class JSONSetting(
    default: BigInt,
    query: String,
    lookUp: Map[String, BigInt] => BigInt,
    min: BigInt = 0,
    max: Option[BigInt] = None) extends IsRuntimeSetting {

  def setWithLUT(lut: Map[String, BigInt]) = set(lookUp(lut))
}

trait HasProgrammableRegisters extends Bundle {
  def registers: Seq[(Data, IsRuntimeSetting)]

  lazy val regMap = Map(registers: _*)

  def getName(dat: Data): String = {
    val name = elements.find(_._2 == dat) match {
      case Some((name, elem)) => name
      case None => throw new RuntimeException("Could not look up register leaf name")
    }
    name
  }

  // Returns the default values for all registered RuntimeSettings
  def getDefaults(prefix: String = ""): Seq[(String, BigInt)] = {
    val localDefaults = registers map { case (elem, reg) => (s"${prefix}${getName(elem)}" -> reg.default) }
    localDefaults ++ (elements flatMap {
      case (name, elem: HasProgrammableRegisters) => elem.getDefaults(s"${prefix}${name}_")
      case _ => Seq()
    })
  }

  // Returns the requested values for all RuntimSEttings, throws an exception if one is unbound
  def getSettings(prefix: String = ""): Seq[(String, String)] = {
    val localSettings = registers map { case (elem, reg) => {
      val name = s"${prefix}${getName(elem)}"
      val setting = reg.getOrElse(throw new RuntimeException(s"Runtime Setting ${name} has not been set"))
      (name -> setting.toString)
      }
    }
    // Recurse into leaves
    localSettings ++ (elements flatMap {
      case (name, elem: HasProgrammableRegisters) => elem.getSettings(s"${prefix}${name}_")
      case _ => Seq()
    })
  }

  // Requests the users input for all unset RuntimeSettings
  def setUnboundSettings(prefix: String = "test"): Unit = {
    // Set all local registers
    registers foreach {
      case (elem, reg) if !reg.isSet() => reg.requestSetting(elem)
      case _ => None
    }
    // Traverse into leaf bundles and set them
    elements foreach {
      case (name, elem: HasProgrammableRegisters) => elem.setUnboundSettings()
      case _ => None
    }
  }
}
