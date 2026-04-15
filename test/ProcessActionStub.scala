package org.polyvariant

object ProcessActionsStub extends ProcessActions {
  def sendSigterm(pid: Int): Either[ProcessActionFailed, String] = Right(pid.toString)
  def sendSigkill(pid: Int): Either[ProcessActionFailed, String] = Right(pid.toString)
  def threadDump(pid: Int): Either[ProcessActionFailed, String] = Right(pid.toString) 
  def heapDump(pid: Int): Either[ProcessActionFailed, String] = Right(pid.toString)
}
