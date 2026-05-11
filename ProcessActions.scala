package org.polyvariant

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import scala.scalanative.posix.{signal => PosixSignal}
import scala.util.control.NonFatal

trait ProcessActions {
  def sendSigterm(pid: Int): Either[ProcessActionFailed, String]
  def sendSigkill(pid: Int): Either[ProcessActionFailed, String]
  def threadDump(pid: Int, kind: String): Either[ProcessActionFailed, String]
  def heapDump(pid: Int, kind: String): Either[ProcessActionFailed, String]
}

class ProcessActionsLive(signal: PosixSignal, debug: Debug, dumpsDir: String) extends ProcessActions {

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

  def threadDump(pid: Int, kind: String): Either[ProcessActionFailed, String] = {
    try {
      debug.log(s"Executing: jcmd $pid Thread.print")
      val dir = Paths.get(dumpsDir, "thread-dumps")
      Files.createDirectories(dir)
      val filename = DumpPaths.dumpFileName("thread", pid, kind, "txt")
      val fullPath = dir.resolve(filename).toString
      val pb = new ProcessBuilder("jcmd", pid.toString, "Thread.print")
      pb.redirectOutput(ProcessBuilder.Redirect.to(new File(fullPath)))
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val finished = proc.waitFor(30, TimeUnit.SECONDS)
      if (!finished) {
        proc.destroyForcibly()
        proc.waitFor()
        debug.log(s"jcmd thread dump timed out for PID $pid")
        Left(ProcessActionFailed(s"jcmd thread dump timed out for PID $pid"))
      } else {
        val exitCode = proc.exitValue()
        if (exitCode == 0) {
          debug.log(s"Thread dump saved to: $fullPath")
          Right(s"Thread dump saved to $fullPath")
        } else {
          debug.log(s"jcmd thread dump failed for PID $pid (exit code $exitCode)")
          Left(ProcessActionFailed(s"jcmd thread dump failed for PID $pid (exit code $exitCode)"))
        }
      }
    } catch {
      case NonFatal(e) =>
        debug.log(s"jcmd failed: ${e.getMessage}")
        Left(ProcessActionFailed(s"jcmd thread dump failed for PID $pid: ${e.getMessage}"))
    }
  }

  def heapDump(pid: Int, kind: String): Either[ProcessActionFailed, String] = {
    try {
      debug.log(s"Executing: jcmd $pid GC.heap_dump")
      val dir = Paths.get(dumpsDir, "heap-dumps")
      Files.createDirectories(dir)
      val filename = DumpPaths.dumpFileName("heap", pid, kind, "hprof")
      val fullPath = dir.resolve(filename).toString
      val pb = new ProcessBuilder("jcmd", pid.toString, "GC.heap_dump", fullPath)
      pb.redirectOutput(new File("/dev/null"))
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val finished = proc.waitFor(60, TimeUnit.SECONDS)
      if (!finished) {
        proc.destroyForcibly()
        proc.waitFor()
        debug.log(s"jcmd heap dump timed out for PID $pid")
        Left(ProcessActionFailed(s"jcmd heap dump timed out for PID $pid"))
      } else {
        val exitCode = proc.exitValue()
        if (exitCode == 0) {
          debug.log(s"Heap dump saved to: $fullPath")
          Right(s"Heap dump saved to $fullPath")
        } else {
          debug.log(s"jcmd heap dump failed for PID $pid (exit code $exitCode)")
          Left(ProcessActionFailed(s"jcmd heap dump failed for PID $pid (exit code $exitCode)"))
        }
      }
    } catch {
      case NonFatal(e) =>
        debug.log(s"jcmd failed: ${e.getMessage}")
        Left(ProcessActionFailed(s"jcmd heap dump failed for PID $pid: ${e.getMessage}"))
    }
  }

}

final case class ProcessActionFailed(msg: String) extends RuntimeException(msg)