package org.polyvariant

import scala.sys.process._
import scala.util.Try
import scala.util.control.NonFatal

trait TerminalSize {
  def query(): (Int, Int)
}

object TerminalSize {
  def apply(debug: Debug): TerminalSize = new SttyTerminalSize(debug)
}

object SttyTerminalSize {
  private val DefaultSize = (80, 24)
  private val MaxWidth = 210
  private val MinWidth = 80
  private val MinHeight = 1

  def parseOutput(output: String): (Int, Int) = {
    val parts = output.trim.split("\\s+")
    if (parts.length == 2) {
      val rows = parts(0).toInt
      val cols = parts(1).toInt
      if (rows > 0 && cols > 0) {
        val width = math.max(MinWidth, math.min(MaxWidth, cols))
        val height = math.max(MinHeight, rows)
        (width, height)
      } else {
        DefaultSize
      }
    } else {
      DefaultSize
    }
  }
}

class SttyTerminalSize(debug: Debug) extends TerminalSize {
  override def query(): (Int, Int) = {
    try {
      val output = Seq("sh", "-c", "stty size < /dev/tty").!!.trim
      val result = SttyTerminalSize.parseOutput(output)
      debug.log(s"TerminalSize: stty output='$output' -> ($result)")
      result
    } catch {
      case NonFatal(e) =>
        debug.log(s"TerminalSize: stty query failed: ${e.getMessage}")
        SttyTerminalSize.DefaultSize
    }
  }
}

case class FixedTerminalSize(w: Int, h: Int) extends TerminalSize {
  override def query(): (Int, Int) = (w, h)
}
