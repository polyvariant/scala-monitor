package org.polyvariant

import java.io.{FileOutputStream, PrintStream}

trait Debug {
  def log(msg: String): Unit
}

object Debug {
  private class Stderr extends Debug {
    def log(msg: String): Unit = System.err.println(s"[DEBUG] $msg")
  }

  private class FileOutput(ps: PrintStream) extends Debug {
    def log(msg: String): Unit = {
      ps.println(s"[DEBUG] $msg")
      ps.flush()
    }
  }

  private class Noop extends Debug {
    def log(msg: String): Unit = ()
  }

  val stderr: Debug = new Stderr
  def toFile(path: String): Debug = new FileOutput(new PrintStream(new FileOutputStream(path, true)))
  val noop: Debug = new Noop
}
