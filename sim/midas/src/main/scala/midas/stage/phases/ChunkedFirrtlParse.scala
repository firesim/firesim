// See LICENSE for license details.

package midas.stage.phases

import java.io.{BufferedReader, File, FileReader}

import scala.collection.mutable

import firrtl.{AnnotationSeq, Parser}
import firrtl.ir.{Circuit, DefModule}
import firrtl.options.{Phase, PreservesAll}
import firrtl.stage.{FirrtlCircuitAnnotation, FirrtlFileAnnotation, InfoModeAnnotation}

/** Parses large FIRRTL text inputs in module-aligned chunks.
  *
  * The ANTLR-based parser behind [[firrtl.stage.phases.AddCircuit]] fails on inputs of roughly 2 GB
  * (observed: a 1.70 GB file parses, a 2.03 GB file of the same design dies with a
  * NullPointerException inside Visitor.visitSubRef although every module in it parses on its own).
  * FIRRTL modules are self-contained at the syntax level, so the file can be split at module
  * boundaries, each piece parsed as a small circuit with the original header, and the module lists
  * concatenated. Peak memory also drops, since only one chunk's token stream is alive at a time.
  *
  * Only [[FirrtlFileAnnotation]]s that name a `.fir` file above `chunkThresholdBytes` are affected;
  * they are replaced by a [[FirrtlCircuitAnnotation]] before AddCircuit runs.
  */
class ChunkedFirrtlParse extends Phase with PreservesAll[Phase] {

  /** Files smaller than this take the stock path. */
  val chunkThresholdBytes: Long = 1L << 30 // 1 GiB

  /** Target size of one parsed piece. */
  val chunkTargetBytes: Long = 256L << 20 // 256 MiB

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val infoMode = annotations.collectFirst { case InfoModeAnnotation(mode) => mode }.getOrElse("use")
    annotations.flatMap {
      case FirrtlFileAnnotation(file) if file.endsWith(".fir") && new File(file).length > chunkThresholdBytes =>
        println(s"[ChunkedFirrtlParse] ${new File(file).length} B FIRRTL input, parsing in ~${chunkTargetBytes >> 20} MiB module-aligned chunks")
        Some(FirrtlCircuitAnnotation(parseInChunks(file, toInfoMode(infoMode, file))))
      case other => Some(other)
    }
  }

  private def toInfoMode(mode: String, file: String): Parser.InfoMode = mode match {
    case "use"    => Parser.UseInfo
    case "ignore" => Parser.IgnoreInfo
    case "gen"    => Parser.GenInfo(file)
    case "append" => Parser.AppendInfo(file)
    case other    => throw new Exception(s"Unknown info mode $other")
  }

  private def isModuleStart(line: String): Boolean =
    line.startsWith("  module ") || line.startsWith("  extmodule ") || line.startsWith("  intmodule ")

  private def parseInChunks(file: String, infoMode: Parser.InfoMode): Circuit = {
    val reader  = new BufferedReader(new FileReader(file), 1 << 20)
    val header  = new StringBuilder // "FIRRTL version ..." (optional) and "circuit X :" lines
    val chunk   = new StringBuilder
    val modules = mutable.ArrayBuffer.empty[DefModule]
    var main: Option[String] = None
    var info:  Option[firrtl.ir.Info] = None
    var nChunks = 0

    def flush(): Unit = if (chunk.nonEmpty) {
      val c = Parser.parseString(header.toString + chunk.toString, infoMode)
      if (main.isEmpty) { main = Some(c.main); info = Some(c.info) }
      modules ++= c.modules
      nChunks += 1
      println(s"[ChunkedFirrtlParse] chunk $nChunks: ${chunk.length} chars, ${c.modules.size} modules (${modules.size} total)")
      chunk.clear()
    }

    try {
      var inHeader = true
      var line = reader.readLine()
      while (line != null) {
        if (inHeader) {
          if (isModuleStart(line)) { inHeader = false }
          else { header.append(line).append('\n') }
        }
        if (!inHeader) {
          if (isModuleStart(line) && chunk.length >= chunkTargetBytes) flush()
          chunk.append(line).append('\n')
        }
        line = reader.readLine()
      }
      flush()
    } finally {
      reader.close()
    }
    require(main.nonEmpty, s"$file contains no modules")
    Circuit(info.get, modules.toSeq, main.get)
  }
}
