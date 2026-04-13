package org.polyvariant

import scala.scalanative.unsafe._
import scala.scalanative.libc.stdio
import scala.scalanative.meta.LinktimeInfo
import java.nio.charset.StandardCharsets

class MacOsProbe(debug: Debug) extends PlatformProbe {

  // proc_pidinfo() "flavor" codes — select which kernel struct to return
  private val ProcPidVnodePathInfo = 9 // struct proc_vnodepathinfo: cwd + root dir paths

  // struct proc_vnodepathinfo holds two vnode entries (cwd, root).
  // Each entry = 152-byte vinfo_stat header + MAXPATHLEN path bytes.
  // The CWD path string starts at byte 152 (immediately after the first header).
  private val VnodeInfoCwdPathOffset = 152
  private val VnodeInfoStructSize = 2352 // 2 x (152 + MAXPATHLEN)

  private val MaxPathLength = 4096

  debug.log(s"Platform: macOS (isMac=${LinktimeInfo.isMac}, isLinux=${LinktimeInfo.isLinux}, is32Bit=${LinktimeInfo.is32BitPlatform})")

  def discover(selfPid: Int): List[ScalaProcess] = {
    val lines = runPsCommand()
    debug.log(s"ps returned ${lines.size} lines")

    val parsed = lines.flatMap { line =>
      val parts = line.trim.split("\\s+", 5)
      if (parts.length == 5) {
        try {
          val pid = parts(0).toInt
          val memPercent = parts(1).toDouble
          val virtualKb = parts(2).toLong
          val residentKb = parts(3).toLong
          val cmdline = parts(4)
          Some((pid, memPercent, virtualKb, residentKb, cmdline))
        } catch {
          case _: NumberFormatException =>
            debug.log(s"  PARSE FAIL: '$line'")
            None
        }
      } else {
        if (line.trim.nonEmpty) debug.log(s"  PARSE FAIL (got ${parts.length} fields, expected 5): '$line'")
        None
      }
    }

    val filteredSelf = parsed.filterNot(_._1 == selfPid)
    debug.log(s"Parsed ${parsed.size} processes, ${filteredSelf.size} after excluding selfPid=$selfPid")

    val matched = filteredSelf.filter { (_, _, _, _, cmdline) =>
      ScalaMonitor.isScalaProcess(cmdline, debug)
    }
    debug.log(s"PIDs passed isScalaProcess: ${matched.size}")

    val results = matched.map { (pid, memPercent, virtualKb, residentKb, cmdline) =>
      val cwd = readProcessWorkingDirectory(pid)
      ScalaProcess(
        pid = pid,
        kind = ScalaMonitor.classify(cmdline, debug),
        residentKb = residentKb,
        virtualKb = virtualKb,
        swapKb = None,
        threads = 0,
        memPercent = memPercent,
        projectPath = cwd.map(ScalaMonitor.shortenPath).getOrElse("unknown")
      )
    }.sortBy(p => -p.residentKb)

    debug.log(s"Discovery summary: ${parsed.size} scanned, ${filteredSelf.size} with cmdline, ${matched.size} passed filter, ${results.size} in output")

    results
  }

  private def runPsCommand(): List[String] = Zone.acquire { implicit z =>
    val cmd = c"ps -eo pid=,%mem=,vsz=,rss=,args= -ww"
    debug.log("Executing: ps -eo pid=,%mem=,vsz=,rss=,args= -ww")
    val stream = popenlib.popen(cmd, c"r")
    if (stream == null) {
      debug.log("popen('ps ...') FAILED - returned null")
      Nil
    } else {
      try {
        val bufSize = 65536
        val buf = alloc[Byte](bufSize)
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        Iterator.continually(stdio.fgets(buf, bufSize, stream.asInstanceOf[Ptr[stdio.FILE]]))
          .takeWhile(_ != null)
          .map(result => fromCString(result).trim)
          .filter(_.nonEmpty)
          .foreach(lines += _)
        lines.toList
      } finally {
        val exitStatus = popenlib.pclose(stream)
        debug.log(s"ps exited with status $exitStatus")
      }
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

}
