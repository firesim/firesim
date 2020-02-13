// See LICENSE for license details.
package midas.core

import logger.Logger

// A wrapper layer around FIRRTL's logger to provide coloured tagging
trait Logging {
  lazy private val logger = new Logger(this.getClass.getName)

  protected def tag(name: String, color: String): String =
    s"[${color}${name}${Console.RESET}] "

  def info(message: => String): Unit  = logger.info(tag("GG Info", Console.GREEN) + message)
  def warn(message: => String): Unit  = logger.warn(tag("GG Warn", Console.YELLOW) + message)
  def error(message: => String): Unit = logger.error(tag("GG Error", Console.RED) + message)
}
