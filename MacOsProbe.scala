package org.polyvariant

import scala.scalanative.unsafe._
import scala.scalanative.libc.stdio
import scala.scalanative.meta.LinktimeInfo
import java.nio.charset.StandardCharsets

class MacOsProbe(debug: Debug) extends PlatformProbe {

  private val ProcPidVnodePathInfo = 9
  private val VnodeInfoCwdPathOffset = 152
  private val VnodeInfoStructSize = 2352
  private val ProcPidTaskInfo = 4
  private val ProcTaskInfoSize = 96

  debug.log(s"Platform: macOS (isMac=${LinktimeInfo.isMac}, isLinux=${LinktimeInfo.isLinux}, is32Bit=${LinktimeInfo.is32BitPlatform})")

  def discover(selfPid: Int): List[ScalaProcess] = {
    val lines = runPsCommand()
    debug.log(s"ps returned ${lines.size} lines")
    MacOsProbe.parsePsLines(
      lines, selfPid, debug,
      cwdResolver = readProcessWorkingDirectory,
      threadCountResolver = readThreadCount
    )
  }

  private def readThreadCount(pid: Int): Int = Zone.acquire { implicit z =>
    val buffer = alloc[Byte](ProcTaskInfoSize)
    val bytesWritten = libproc.proc_pidinfo(pid, ProcPidTaskInfo, 0L, buffer, ProcTaskInfoSize)
    if (bytesWritten <= 0) 0
    else {
      val ptr = (buffer + 84).asInstanceOf[Ptr[CInt]]
      !ptr
    }
  }

  private def readProcessWorkingDirectory(pid: Int): Option[String] = Zone.acquire { implicit z =>
    val buffer = alloc[Byte](VnodeInfoStructSize)
    val bytesWritten = libproc.proc_pidinfo(pid, ProcPidVnodePathInfo, 0L, buffer, VnodeInfoStructSize)
    if (bytesWritten <= 0) {
      debug.log(s"PID $pid: proc_pidinfo(ProcPidVnodePathInfo) FAILED (bytesWritten=$bytesWritten)")
      None
    } else {
      val cwdPtr = (buffer + VnodeInfoCwdPathOffset).asInstanceOf[CString]
      val cwd = fromCString(cwdPtr, StandardCharsets.UTF_8)
      debug.log(s"PID $pid: cwd -> $cwd")
      if (cwd.nonEmpty) Some(cwd) else None
    }
  }

  private def runPsCommand(): List[String] = Zone.acquire { implicit z =>
    val cmd = c"ps -eo pid=,%mem=,rss=,args= -ww"
    debug.log("Executing: ps -eo pid=,%mem=,rss=,args= -ww")
    val stream = popenlib.popen(cmd, c"r")
    if (stream == null) {
      debug.log("popen('ps ...') FAILED - returned null")
      Nil
    } else {
      try {
        val bufSize = 65536
        val buf = alloc[Byte](bufSize)
        Iterator.continually(stdio.fgets(buf, bufSize, stream.asInstanceOf[Ptr[stdio.FILE]]))
          .takeWhile(_ != null)
          .map(result => fromCString(result).trim)
          .filter(_.nonEmpty)
          .toList
      } finally {
        val exitStatus = popenlib.pclose(stream)
        debug.log(s"ps exited with status $exitStatus")
      }
    }
  }

}

object MacOsProbe {

  def parsePsLines(
    lines: List[String],
    selfPid: Int,
    debug: Debug,
    cwdResolver: Int => Option[String],
    threadCountResolver: Int => Int = _ => 0
  ): List[ScalaProcess] = {
    val parsed = lines.flatMap { line =>
      val parts = line.trim.split("\\s+", 4)
      if (parts.length == 4) {
        try {
          val pid = parts(0).toInt
          val memPercent = parts(1).replace(',', '.').toDouble
          val residentKb = parts(2).toLong
          val cmdline = parts(3)
          Some((pid, memPercent, residentKb, cmdline))
        } catch {
          case _: NumberFormatException =>
            debug.log(s"  PARSE FAIL: '$line'")
            None
        }
      } else {
        if (line.trim.nonEmpty) debug.log(s"  PARSE FAIL (got ${parts.length} fields, expected 4): '$line'")
        None
      }
    }

    val filteredSelf = parsed.filterNot(_._1 == selfPid)
    debug.log(s"Parsed ${parsed.size} processes, ${filteredSelf.size} after excluding selfPid=$selfPid")

    val matched = filteredSelf.filter { (_, _, _, cmdline) =>
      ScalaMonitor.isScalaProcess(cmdline, debug)
    }
    debug.log(s"PIDs passed isScalaProcess: ${matched.size}")

    val results = matched.map { (pid, memPercent, residentKb, cmdline) =>
      val cwd = cwdResolver(pid)
      ScalaProcess(
        pid = pid,
        kind = ScalaMonitor.classify(cmdline, debug),
        ramKb = residentKb,
        swapKb = None,
        threads = threadCountResolver(pid),
        memPercent = memPercent,
        projectPath = cwd.map(ScalaMonitor.shortenPath).getOrElse("unknown")
      )
    }.sortBy(p => -p.ramKb)

    debug.log(s"Discovery summary: ${parsed.size} scanned, ${filteredSelf.size} with cmdline, ${matched.size} passed filter, ${results.size} in output")

    results
  }

}
