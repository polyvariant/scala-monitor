package org.polyvariant

object ProcessActionsStub extends ProcessActions {
  def sendSigterm(pid: Int): Either[ProcessActionFailed, String] = Right(s"SIGTERM sent to PID $pid")
  def sendSigkill(pid: Int): Either[ProcessActionFailed, String] = Right(s"SIGKILL sent to PID $pid")
  def threadDump(pid: Int, kind: String): Either[ProcessActionFailed, String] =
    Right(s"Thread dump for PID $pid ($kind)")
  def heapDump(pid: Int, kind: String): Either[ProcessActionFailed, String] =
    Right(s"Heap dump for PID $pid ($kind)")
}
