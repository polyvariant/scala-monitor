package org.polyvariant

case class ScalaProcess(
  pid: Int,
  ppid: Int,
  kind: String,
  ramKb: Long,
  swapKb: Option[Long],
  threads: Int,
  memPercent: Double,
  projectPath: String
)

trait PlatformProbe {
  def discover(selfPid: Int): List[ScalaProcess]
}
