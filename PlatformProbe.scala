package org.polyvariant

case class ScalaProcess(
  pid: Int,
  kind: String,
  residentKb: Long,
  virtualKb: Long,
  swapKb: Option[Long],
  threads: Int,
  memPercent: Double,
  projectPath: String
)

trait PlatformProbe {
  def discover(selfPid: Int): List[ScalaProcess]
}
