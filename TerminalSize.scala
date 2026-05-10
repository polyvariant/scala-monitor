package org.polyvariant

import scala.sys.process._
import scala.util.Try

trait TerminalSize {
  def query(): (Int, Int)
}

object SttyTerminalSize extends TerminalSize {
  private val DefaultSize = (80, 24)
  private val MaxWidth = 210
  private val MinWidth = 80
  private val MinHeight = 1

  override def query(): (Int, Int) = {
    Try {
      val output = Seq("sh", "-c", "stty size < /dev/tty").!!.trim
      val parts = output.split("\\s+")
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
    }.getOrElse(DefaultSize)
  }
}

case class FixedTerminalSize(w: Int, h: Int) extends TerminalSize {
  override def query(): (Int, Int) = (w, h)
}
