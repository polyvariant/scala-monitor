package org.polyvariant

import scala.scalanative.posix.{signal => PosixSignal}

trait ProcessActions {
  def sendSigterm(pid: Int): Either[ProcessActionFailed, String]
  def sendSigkill(pid: Int): Either[ProcessActionFailed, String]
  def threadDump(pid: Int): Either[ProcessActionFailed, String] 
  def heapDump(pid: Int): Either[ProcessActionFailed, String]
}

class ProcessActionsLive(signal: PosixSignal) extends ProcessActions {

  def sendSigterm(pid: Int): Either[ProcessActionFailed, String] =
    if(pid <= 0) Left(ProcessActionFailed(s"Only positive pid allowed, got: $pid"))
    else {
      val result = signal.kill(pid, signal.SIGTERM)
      if (result == 0) Right(s"SIGTERM sent to PID $pid")
      else Left(ProcessActionFailed(s"kill($pid, SIGTERM) failed"))
    }

  def sendSigkill(pid: Int): Either[ProcessActionFailed, String] = 
    if(pid <= 0) Left(ProcessActionFailed(s"Only positive pid allowed, got: $pid"))
    else {
      val result = signal.kill(pid, signal.SIGKILL)
      if (result == 0) Right(s"SIGKILL sent to PID $pid")
      else Left(ProcessActionFailed(s"kill($pid, SIGKILL) failed"))
    }

  def threadDump(pid: Int): Either[ProcessActionFailed, String] = {
    // TODO to be implemented in future PR
    Right(s"Thread dump requested for PID $pid -> /tmp/threads-$pid.hprof")
  }

  def heapDump(pid: Int): Either[ProcessActionFailed, String] = {
    // TODO to be implemented in future PR
    Right(s"Heap dump requested for PID $pid -> /tmp/heap-$pid.hprof")
  }

}

final case class ProcessActionFailed(msg: String) extends RuntimeException(msg)