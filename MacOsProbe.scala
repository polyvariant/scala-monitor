import scala.annotation.tailrec
import scala.scalanative.unsafe._
import java.nio.charset.StandardCharsets

case class ProcessMemoryInfo(virtualKb: Long, residentKb: Long, threads: Int)

object MacOsProbe extends PlatformProbe {

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

  def discover(selfPid: Int): List[ScalaProcess] = {
    val totalRamKb = queryTotalPhysicalMemoryKb()
    val pids = listAllRunningPids()
    pids.filterNot(_ == selfPid).flatMap { pid =>
      for {
        cmdline <- readProcessCmdline(pid)
        if ScalaMonitor.isScalaProcess(cmdline)
        info    <- readProcessMemoryAndThreads(pid)
        cwd     <- readProcessWorkingDirectory(pid)
      } yield ScalaProcess(
        pid = pid,
        kind = ScalaMonitor.classify(cmdline),
        residentKb = info.residentKb,
        virtualKb = info.virtualKb,
        swapKb = None,
        threads = info.threads,
        memPercent = info.residentKb.toDouble / totalRamKb * 100.0,
        projectPath = ScalaMonitor.shortenPath(cwd)
      )
    }.sortBy(p => -p.residentKb)
  }

  private def queryTotalPhysicalMemoryKb(): Long = Zone.acquire { implicit z =>
    val resultPtr = alloc[Long](1)
    val resultSizePtr = alloc[Long](1)
    !resultSizePtr = 8L
    if (macsysctl.sysctlbyname(c"hw.memsize", resultPtr.asInstanceOf[Ptr[Byte]], resultSizePtr, null, 0L) == 0)
      (!resultPtr) / 1024
    else 1L
  }

  private def listAllRunningPids(): List[Int] = Zone.acquire { implicit z =>
    val MaxPidCount = 4096
    val pidBuffer = alloc[Int](MaxPidCount)
    // Returns bytes written, not count — divide by sizeof(int) to get pid count
    val bytesWritten = libproc.proc_listallpids(pidBuffer, MaxPidCount * 4)
    if (bytesWritten <= 0) Nil
    else (0 until bytesWritten / 4).map(i => !(pidBuffer + i)).toList
  }

  private def readProcessMemoryAndThreads(pid: Int): Option[ProcessMemoryInfo] = Zone.acquire { implicit z =>
    val buffer = alloc[Byte](TaskInfoStructSize)
    val bytesWritten = libproc.proc_pidinfo(pid, ProcPidTaskInfo, 0L, buffer, TaskInfoStructSize)
    if (bytesWritten <= 0) None
    else {
      val virtualKb = readLongAt(buffer, TaskInfoVirtualSizeOffset) / 1024
      val residentKb = readLongAt(buffer, TaskInfoResidentSizeOffset) / 1024
      val threads = readIntAt(buffer, TaskInfoThreadCountOffset)
      Some(ProcessMemoryInfo(virtualKb, residentKb, threads))
    }
  }

  private def readProcessCmdline(pid: Int): Option[String] =
    readCmdlineViaSysctl(pid).orElse(readExecutablePathAsFallback(pid))

  // macOS exposes per-process command-line arguments via sysctl kern.procargs2.<pid>.
  // The returned buffer layout:
  //   [0..3]   int32 argc
  //   [4..]    exec path (NUL-terminated), padding NULs, then argc NUL-separated arg strings
  // Buffer size must be allocated to kern.argmax (system-wide max).
  private def readCmdlineViaSysctl(pid: Int): Option[String] = Zone.acquire { implicit z =>
    queryMaxArgsBufferSize() match {
      case None => None
      case Some(maxArgsSize) =>
        val argsBuffer = alloc[Byte](maxArgsSize)
        val argsBufferSizePtr = alloc[Long](1)
        !argsBufferSizePtr = maxArgsSize.toLong

        val procArgsMib = buildSysctlMib(SysctlKern, SysctlKernProcArgs2, pid)
        if (macsysctl.sysctl(procArgsMib, 3, argsBuffer, argsBufferSizePtr, null, 0L) != 0) {
          None
        } else {
          val bytesWritten = (!argsBufferSizePtr).toInt
          if (bytesWritten < 4) None
          else {
            val argumentCount = readIntAt(argsBuffer, 0)
            val rawBytes = (0 until bytesWritten).map(i => !(argsBuffer + i)).toArray
            val arguments = extractArgumentsFromProcBuffer(argumentCount, rawBytes)
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
    if (macsysctl.sysctl(mib, 2, resultPtr.asInstanceOf[Ptr[Byte]], resultSizePtr, null, 0L) != 0) None
    else Some(!resultPtr)
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

  // Fallback: when sysctl args unavailable, use just the executable path
  private def readExecutablePathAsFallback(pid: Int): Option[String] = Zone.acquire { implicit z =>
    val pathBuffer = alloc[Byte](MaxPathLength)
    val pathLength = libproc.proc_pidpath(pid, pathBuffer, MaxPathLength)
    if (pathLength > 0) Some(fromCString(pathBuffer.asInstanceOf[CString], StandardCharsets.UTF_8))
    else None
  }

  private def readProcessWorkingDirectory(pid: Int): Option[String] = Zone.acquire { implicit z =>
    val buffer = alloc[Byte](VnodeInfoStructSize)
    val bytesWritten = libproc.proc_pidinfo(pid, ProcPidVnodePathInfo, 0L, buffer, VnodeInfoStructSize)
    if (bytesWritten <= 0) {
      None
    } else {
      val cwdPtr = (buffer + VnodeInfoCwdPathOffset).asInstanceOf[CString]
      val cwd = fromCString(cwdPtr, StandardCharsets.UTF_8)
      if (cwd.nonEmpty) Some(cwd) else None
    }
  }

  private def readLongAt(base: Ptr[Byte], byteOffset: Int): Long =
    (!(base + byteOffset).asInstanceOf[Ptr[Long]])

  private def readIntAt(base: Ptr[Byte], byteOffset: Int): Int =
    (!(base + byteOffset).asInstanceOf[Ptr[Int]])
}
