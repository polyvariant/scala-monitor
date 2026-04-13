package org.polyvariant

class Debug(enabled: Boolean) {
  def log(msg: String): Unit =
    if enabled then System.err.println(s"[DEBUG] $msg")
}
