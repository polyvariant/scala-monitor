package org.polyvariant

object ProcessActionsStub extends ProcessActions {
  def sendSigterm(pid: Int): Either[ProcessActionFailed, String] = Right(s"SIGTERM sent to PID $pid")
  def sendSigkill(pid: Int): Either[ProcessActionFailed, String] = Right(s"SIGKILL sent to PID $pid")
  def threadDump(pid: Int): Either[ProcessActionFailed, String] = 
    Right(s"Thread dump requested for PID $pid -> /tmp/threads-$pid.hprof")
  def heapDump(pid: Int): Either[ProcessActionFailed, String] = 
    Right(s"Heap dump requested for PID $pid -> /tmp/heap-$pid.hprof")
}
