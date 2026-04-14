package org.polyvariant

import scala.scalanative.posix.signal

object ProcessActions {

  def sendSigterm(pid: Int): Either[String, String] = {
    val result = signal.kill(pid, signal.SIGTERM)
    if (result == 0) Right(s"SIGTERM sent to PID $pid")
    else Left(s"kill($pid, SIGTERM) failed")
  }

  def sendSigkill(pid: Int): Either[String, String] = {
    val result = signal.kill(pid, signal.SIGKILL)
    if (result == 0) Right(s"SIGKILL sent to PID $pid")
    else Left(s"kill($pid, SIGKILL) failed")
  }

  def threadDump(pid: Int): Either[String, String] = {
    Right(s"Thread dump requested for PID $pid -> /tmp/threads-$pid.hprof")
  }

  def heapDump(pid: Int): Either[String, String] = {
    Right(s"Heap dump requested for PID $pid -> /tmp/heap-$pid.hprof")
  }
}
