package firesim

import java.io.File
import scala.io.Source
import collection.mutable

object TestSuiteUtil {

  /** Extracts all lines in a file that begin with a specific prefix, removing extra whitespace between the prefix and
    * the remainder of the line
    *
    * @param filename
    *   Input file
    * @param prefix
    *   The per-line prefix to filter with
    * @param linesToDrop
    *   Some number of matched lines to be removed
    * @param headerLines
    *   An initial number of lines to drop before filtering. Assertions, Printf output have a single line header. MLsim
    *   stdout has some unused output, so set this to 1 by default
    */
  def extractLines(filename: File, prefix: String, linesToDrop: Int = 0, headerLines: Int = 1): Seq[String] = {
    val lines = Source.fromFile(filename).getLines.toList.drop(headerLines)
    lines
      .filter(_.startsWith(prefix))
      .dropRight(linesToDrop)
      .map(_.stripPrefix(prefix).replaceAll(" +", " "))
  }

  /** Diffs two sets of lines. Wrap calls to this function in a scalatest behavior spec. @param aName and @param bName
    * can be used to provide more insightful assertion messages in scalatest reporting.
    */
  def diffLines(
    aLines: Seq[String],
    bLines: Seq[String],
    aName:  String = "Actual output",
    bName:  String = "Expected output",
  ): Unit = {
    assert(
      aLines.size == bLines.size && aLines.nonEmpty,
      s"\n${aName} length (${aLines.size}) and ${bName} length (${bLines.size}) differ.",
    )
    for ((a, b) <- bLines.zip(aLines)) {
      assert(a == b)
    }
  }

  /** Splits a CSV row in substrings, removing whitespace on either end */
  def splitAtCommas(s: String) = s.split(",").map(_.trim)

  def parseCSV(file: File): Map[String, Seq[String]] = {
    val header :: valueRows = Source.fromFile(file).getLines.toList
    val headerFields        = splitAtCommas(header)

    val columns = Seq.fill(headerFields.size)(mutable.ArrayBuffer[String]())
    for (row <- valueRows) {
      val values = splitAtCommas(row)
      assert(columns.size == values.size, s"CSV file ${file} must have a uniform number of values per row")
      columns.zip(values).foreach { case (buffer, value) => buffer.append(value) }
    }

    Map(headerFields.zip(columns.map(_.toSeq)): _*)
  }
}
