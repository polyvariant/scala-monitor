import scala.annotation.tailrec
import scala.scalanative.unsafe._
import java.nio.charset.StandardCharsets

case class ProcessMemoryInfo(virtualKb: Long, residentKb: Long, threads: Int)

object MacOsProbe extends PlatformProbe {

  private val PROC_PIDTASKINFO = 4
  private val PROC_PIDVNODEPATHINFO = 9

  private val CTL_KERN = 1
  private val KERN_ARGMAX = 8
  private val KERN_PROCARGS2 = 49

  // Byte offsets into proc_taskinfo struct (bsd/sys/proc_info.h):
  //   0  = pti_virtual_size  (uint64), 8 = pti_resident_size (uint64),
  //   64 = pti_threadnum     (int32) — after 8 × int32 fields
  private val PtiVirtualSizeOffset = 0
  private val PtiResidentSizeOffset = 8
  private val PtiThreadNumByteOffset = 64

  // pvp_cdir.vip_path offset = sizeof(struct vinfo_stat) = 152
  // sizeof(struct proc_vnodepathinfo) = 2 × (152 + MAXPATHLEN) = 2352
  private val VnodePathCwdOffset = 152
  private val VnodePathBufSize = 2352
  private val PathMax = 4096

  def discover(selfPid: Int): List[ScalaProcess] = {
    val totalRamKb = getTotalRamKb()
    val pids = listAllPids()
    pids.filterNot(_ == selfPid).flatMap { pid =>
      for {
        cmdline <- getCmdline(pid)
        if ScalaMonitor.isScalaProcess(cmdline)
        info    <- getProcessInfo(pid)
        cwd     <- getCwd(pid)
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

  def getTotalRamKb(): Long = Zone.acquire { implicit z =>
    val memsize = alloc[Long](1)
    val len = alloc[Long](1)
    !len = 8L
    if (macsysctl.sysctlbyname(c"hw.memsize", memsize.asInstanceOf[Ptr[Byte]], len, null, 0L) == 0)
      (!memsize) / 1024
    else 1L
  }

  private def listAllPids(): List[Int] = Zone.acquire { implicit z =>
    val maxPids = 4096
    val buf = alloc[Int](maxPids)
    val actual = libproc.proc_listallpids(buf, maxPids * 4)
    if (actual <= 0) Nil
    else (0 until actual / 4).map(i => !(buf + i)).toList
  }

  private def getProcessInfo(pid: Int): Option[ProcessMemoryInfo] = Zone.acquire { implicit z =>
    val buf = alloc[Long](32)
    val ret = libproc.proc_pidinfo(pid, PROC_PIDTASKINFO, 0L, buf.asInstanceOf[Ptr[Byte]], 256)
    if (ret <= 0) None
    else {
      val bytePtr = buf.asInstanceOf[Ptr[Byte]]
      val virtualKb = (!(buf + (PtiVirtualSizeOffset / 8))) / 1024
      val residentKb = (!(buf + (PtiResidentSizeOffset / 8))) / 1024
      val threadsPtr = (bytePtr + PtiThreadNumByteOffset).asInstanceOf[Ptr[Int]]
      val threads = (!threadsPtr)
      Some(ProcessMemoryInfo(virtualKb, residentKb, threads))
    }
  }

  private def getCmdline(pid: Int): Option[String] =
    getCmdlineSysctl(pid).orElse(getPathFallback(pid))

  private def getCmdlineSysctl(pid: Int): Option[String] = Zone.acquire { implicit z =>
    val mib1 = alloc[Int](2)
    !mib1 = CTL_KERN
    !(mib1 + 1) = KERN_ARGMAX
    val argmaxVal = alloc[Int](1)
    val argmaxLen = alloc[Long](1)
    !argmaxLen = 4L
    if (macsysctl.sysctl(mib1, 2, argmaxVal.asInstanceOf[Ptr[Byte]], argmaxLen, null, 0L) != 0) {
      None
    } else {
      val argmax = (!argmaxVal)

      val mib2 = alloc[Int](3)
      !mib2 = CTL_KERN
      !(mib2 + 1) = KERN_PROCARGS2
      !(mib2 + 2) = pid
      val buf = alloc[Byte](argmax)
      val bufLen = alloc[Long](1)
      !bufLen = argmax.toLong

      if (macsysctl.sysctl(mib2, 3, buf, bufLen, null, 0L) != 0) {
        None
      } else {
        val len = (!bufLen).toInt
        if (len < 4) {
          None
        } else {
          val argc = (!(buf.asInstanceOf[Ptr[Int]])).toInt
          val bytes = (0 until len).map(i => !(buf + i)).toArray
          val args = parseKernProcArgs(argc, bytes, 4)
          if (args.nonEmpty) Some(args.mkString(" "))
          else None
        }
      }
    }
  }

  private def parseKernProcArgs(argc: Int, bytes: Array[Byte], startFrom: Int): List[String] = {
    @tailrec
    def skipExecPath(pos: Int): Int =
      if (pos >= bytes.length || bytes(pos) == 0) pos
      else skipExecPath(pos + 1)

    @tailrec
    def skipNulSep(pos: Int): Int =
      if (pos >= bytes.length || bytes(pos) != 0) pos
      else skipNulSep(pos + 1)

    val argsStart = skipNulSep(skipExecPath(startFrom))

    @tailrec
    def readArgs(pos: Int, remaining: Int, acc: List[String]): List[String] =
      if (remaining <= 0 || pos >= bytes.length) acc.reverse
      else {
        val end = bytes.indexOf(0.toByte, pos)
        if (end < 0) acc.reverse
        else if (end == pos) readArgs(pos + 1, remaining, acc)
        else readArgs(end + 1, remaining - 1, new String(bytes, pos, end - pos, StandardCharsets.UTF_8) :: acc)
      }

    readArgs(argsStart, argc, Nil)
  }

  private def getPathFallback(pid: Int): Option[String] = Zone.acquire { implicit z =>
    val buf = alloc[Byte](PathMax)
    val ret = libproc.proc_pidpath(pid, buf, PathMax)
    if (ret > 0) Some(fromCString(buf.asInstanceOf[CString], StandardCharsets.UTF_8))
    else None
  }

  private def getCwd(pid: Int): Option[String] = Zone.acquire { implicit z =>
    val buf = alloc[Byte](VnodePathBufSize)
    val ret = libproc.proc_pidinfo(pid, PROC_PIDVNODEPATHINFO, 0L, buf, VnodePathBufSize)
    if (ret <= 0) {
      None
    } else {
      val cwdPtr = (buf + VnodePathCwdOffset).asInstanceOf[CString]
      val cwd = fromCString(cwdPtr, StandardCharsets.UTF_8)
      if (cwd.nonEmpty) Some(cwd) else None
    }
  }
}
