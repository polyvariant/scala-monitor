package org.polyvariant

import scala.annotation.tailrec
import scala.scalanative.unsafe._
import scala.scalanative.meta.LinktimeInfo
import java.nio.charset.StandardCharsets

case class ProcessMemoryInfo(virtualKb: Long, residentKb: Long, threads: Int)

class MacOsProbe(debug: Debug) extends PlatformProbe {

  // proc_pidinfo() "flavor" codes — select which kernel struct to return
  private val ProcPidTaskInfo = 4      // struct proc_taskinfo: memory stats + thread count
  private val ProcPidVnodePathInfo = 9 // struct proc_vnodepathinfo: cwd + root dir paths

  // sysctl(3) MIB components — macOS identifies kernel params via integer arrays,
  // analogous to filesystem paths: [CTL_KERN, KERN_ARGMAX] → kern.argmax
  private val SysctlKern = 1
  private val SysctlKernArgmax = 8       // max size of a process' args+env buffer
  private val SysctlKernProcArgs2 = 49   // per-process args+env (includes argc prefix)

  // struct proc_taskinfo byte layout (XNU bsd/sys/proc_info.h):
  //   0..7:   pti_virtual_size   (uint64)    8..15: pti_resident_size (uint64)
  //   16..63: 8 × uint32 scheduling fields    64..67: pti_threadnum (int32)
  private val TaskInfoVirtualSizeOffset = 0
  private val TaskInfoResidentSizeOffset = 8
  private val TaskInfoThreadCountOffset = 64
  private val TaskInfoStructSize = 256

  // struct proc_vnodepathinfo holds two vnode entries (cwd, root).
  // Each entry = 152-byte vinfo_stat header + MAXPATHLEN path bytes.
  // The CWD path string starts at byte 152 (immediately after the first header).
  private val VnodeInfoCwdPathOffset = 152
  private val VnodeInfoStructSize = 2352 // 2 × (152 + MAXPATHLEN)

  private val MaxPathLength = 4096

  debug.log(s"Platform: macOS (isMac=${LinktimeInfo.isMac}, isLinux=${LinktimeInfo.isLinux}, is32Bit=${LinktimeInfo.is32BitPlatform})")

  def discover(selfPid: Int): List[ScalaProcess] = {
    val totalRamKb = queryTotalPhysicalMemoryKb()
    val pids = listAllRunningPids()
    val candidates = pids.filterNot(_ == selfPid)

    debug.log(s"Self PID: $selfPid, Total PIDs found: ${candidates.size}")

    val withCmdline = candidates.flatMap(pid => readProcessCmdline(pid).map((pid, _)))
    debug.log(s"PIDs with cmdline: ${withCmdline.size}")

    val matched = withCmdline.filter((_, cmdline) => ScalaMonitor.isScalaProcess(cmdline, debug))
    debug.log(s"PIDs passed isScalaProcess: ${matched.size}")

    val results = matched.flatMap { (pid, cmdline) =>
      for {
        info <- readProcessMemoryAndThreads(pid)
        cwd  <- readProcessWorkingDirectory(pid)
      } yield ScalaProcess(
        pid = pid,
        kind = ScalaMonitor.classify(cmdline, debug),
        residentKb = info.residentKb,
        virtualKb = info.virtualKb,
        swapKb = None,
        threads = info.threads,
        memPercent = info.residentKb.toDouble / totalRamKb * 100.0,
        projectPath = ScalaMonitor.shortenPath(cwd)
      )
    }.sortBy(p => -p.residentKb)

    debug.log(s"Discovery summary: ${candidates.size} scanned, ${withCmdline.size} with cmdline, ${matched.size} passed filter, ${results.size} in output")

    results
  }

  private def queryTotalPhysicalMemoryKb(): Long = Zone.acquire { implicit z =>
    val resultPtr = alloc[Long](1)
    val resultSizePtr = alloc[Long](1)
    !resultSizePtr = 8L
    val ret = macsysctl.sysctlbyname(c"hw.memsize", resultPtr.asInstanceOf[Ptr[Byte]], resultSizePtr, null, 0L)
    if (ret == 0) {
      val kb = (!resultPtr) / 1024
      debug.log(s"Total physical memory: ${kb} KB (sysctl hw.memsize returned $ret)")
      kb
    } else {
      debug.log(s"sysctl(hw.memsize) FAILED with return code $ret — using fallback of 1 KB")
      1L
    }
  }

  private def listAllRunningPids(): List[Int] = Zone.acquire { implicit z =>
    val MaxPidCount = 4096
    val bufferSize = MaxPidCount * 4
    val pidBuffer = alloc[Int](MaxPidCount)
    // Returns bytes written, not count — divide by sizeof(int) to get pid count
    val bytesWritten = libproc.proc_listallpids(pidBuffer, bufferSize)

    debug.log(s"proc_listallpids: bytesWritten=$bytesWritten, bufferSize=$bufferSize")

    if (bytesWritten <= 0) {
      debug.log(s"proc_listallpids FAILED or returned empty (bytesWritten=$bytesWritten)")
      Nil
    } else {
      if (bytesWritten == bufferSize) {
        debug.log(s"WARNING: bytesWritten == bufferSize ($bufferSize) — PID buffer may be truncated, some PIDs lost")
      }
      val pids = (0 until bytesWritten / 4).map(i => !(pidBuffer + i)).toList
      debug.log(s"PID count: ${pids.size}")
      pids
    }
  }

  private def readProcessMemoryAndThreads(pid: Int): Option[ProcessMemoryInfo] = Zone.acquire { implicit z =>
    val buffer = alloc[Byte](TaskInfoStructSize)
    val bytesWritten = libproc.proc_pidinfo(pid, ProcPidTaskInfo, 0L, buffer, TaskInfoStructSize)
    if (bytesWritten <= 0) {
      debug.log(s"PID $pid: proc_pidinfo(ProcPidTaskInfo) FAILED (bytesWritten=$bytesWritten)")
      None
    } else {
      val virtualKb = readLongAt(buffer, TaskInfoVirtualSizeOffset) / 1024
      val residentKb = readLongAt(buffer, TaskInfoResidentSizeOffset) / 1024
      val threads = readIntAt(buffer, TaskInfoThreadCountOffset)
      debug.log(s"PID $pid: memory OK — virtual=${virtualKb}KB, resident=${residentKb}KB, threads=$threads")
      Some(ProcessMemoryInfo(virtualKb, residentKb, threads))
    }
  }

  private def readProcessCmdline(pid: Int): Option[String] =
    readCmdlineViaSysctl(pid).orElse(readExecutablePathAsFallback(pid))

  private def readCmdlineViaSysctl(pid: Int): Option[String] = Zone.acquire { implicit z =>
    queryMaxArgsBufferSize() match {
      case None => None
      case Some(maxArgsSize) =>
        val argsBuffer = alloc[Byte](maxArgsSize)
        val argsBufferSizePtr = alloc[Long](1)
        !argsBufferSizePtr = maxArgsSize.toLong

        val procArgsMib = buildSysctlMib(SysctlKern, SysctlKernProcArgs2, pid)
        val sysctlRet = macsysctl.sysctl(procArgsMib, 3, argsBuffer, argsBufferSizePtr, null, 0L)
        if (sysctlRet != 0) {
          debug.log(s"PID $pid: sysctl(KERN_PROCARGS2) FAILED (return=$sysctlRet)")
          None
        } else {
          val bytesWritten = (!argsBufferSizePtr).toInt
          if (bytesWritten < 4) {
            debug.log(s"PID $pid: sysctl(KERN_PROCARGS2) returned bytesWritten=$bytesWritten (< 4), insufficient for argc header")
            None
          } else {
            val argumentCount = readIntAt(argsBuffer, 0)
            val rawBytes = (0 until bytesWritten).map(i => !(argsBuffer + i)).toArray
            val arguments = extractArgumentsFromProcBuffer(argumentCount, rawBytes)
            debug.log(s"PID $pid: sysctl(KERN_PROCARGS2) OK — bytesWritten=$bytesWritten, argc=$argumentCount, args=[${arguments.mkString(", ")}]")
            if (arguments.nonEmpty) Some(arguments.mkString(" "))
            else None
          }
        }
    }
  }

  private def queryMaxArgsBufferSize(): Option[Int] = Zone.acquire { implicit z =>
    val resultPtr = alloc[Int](1)
    val resultSizePtr = alloc[Long](1)
    !resultSizePtr = 4L
    val mib = buildSysctlMib(SysctlKern, SysctlKernArgmax)
    val ret = macsysctl.sysctl(mib, 2, resultPtr.asInstanceOf[Ptr[Byte]], resultSizePtr, null, 0L)
    if (ret != 0) {
      debug.log(s"sysctl(KERN_ARGMAX) FAILED (return=$ret) — sysctl cmdline path disabled, falling back to proc_pidpath only")
      None
    } else {
      val maxArgs = !resultPtr
      debug.log(s"kern.argmax = $maxArgs bytes")
      Some(maxArgs)
    }
  }

  private def buildSysctlMib(components: Int*)(implicit z: Zone): Ptr[Int] = {
    val mib = alloc[Int](components.size)
    components.zipWithIndex.foreach { (value, i) => !(mib + i) = value }
    mib
  }

  // After the 4-byte argc header, the buffer contains:
  //   [execPath\0][padding \0s][arg0\0][arg1\0]...[argN\0]
  private def extractArgumentsFromProcBuffer(argumentCount: Int, buffer: Array[Byte]): List[String] = {
    val afterArgcHeader = 4

    @tailrec
    def skipOverExecPath(pos: Int): Int =
      if (pos >= buffer.length || buffer(pos) == 0) pos
      else skipOverExecPath(pos + 1)

    @tailrec
    def skipPaddingNuls(pos: Int): Int =
      if (pos >= buffer.length || buffer(pos) != 0) pos
      else skipPaddingNuls(pos + 1)

    val argsStart = skipPaddingNuls(skipOverExecPath(afterArgcHeader))

    @tailrec
    def readNulSeparatedStrings(pos: Int, remaining: Int, acc: List[String]): List[String] =
      if (remaining <= 0 || pos >= buffer.length) acc.reverse
      else {
        val nulPos = buffer.indexOf(0.toByte, pos)
        if (nulPos < 0) acc.reverse
        else if (nulPos == pos) readNulSeparatedStrings(pos + 1, remaining, acc)
        else readNulSeparatedStrings(nulPos + 1, remaining - 1, new String(buffer, pos, nulPos - pos, StandardCharsets.UTF_8) :: acc)
      }

    readNulSeparatedStrings(argsStart, argumentCount, Nil)
  }

  private def readExecutablePathAsFallback(pid: Int): Option[String] = Zone.acquire { implicit z =>
    val pathBuffer = alloc[Byte](MaxPathLength)
    val pathLength = libproc.proc_pidpath(pid, pathBuffer, MaxPathLength)
    if (pathLength <= 0) {
      debug.log(s"PID $pid: proc_pidpath FAILED (return=$pathLength) — no cmdline available")
      None
    } else {
      val path = fromCString(pathBuffer.asInstanceOf[CString], StandardCharsets.UTF_8)
      debug.log(s"PID $pid: proc_pidpath fallback → $path")
      Some(path)
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
      debug.log(s"PID $pid: cwd → $cwd")
      if (cwd.nonEmpty) Some(cwd) else None
    }
  }

  private def readLongAt(base: Ptr[Byte], byteOffset: Int): Long =
    (!(base + byteOffset).asInstanceOf[Ptr[Long]])

  private def readIntAt(base: Ptr[Byte], byteOffset: Int): Int =
    (!(base + byteOffset).asInstanceOf[Ptr[Int]])
}
