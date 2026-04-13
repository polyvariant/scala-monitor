package org.polyvariant

import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.scalanative.meta.LinktimeInfo
import scala.util.Try

class LinuxProbe(debug: Debug) extends PlatformProbe {

  private val memTotalPattern = raw"""MemTotal:\s+(\d+)\s+kB""".r

  debug.log(s"Platform: Linux (isMac=${LinktimeInfo.isMac}, isLinux=${LinktimeInfo.isLinux}, is32Bit=${LinktimeInfo.is32BitPlatform})")

  def discover(selfPid: Int): List[ScalaProcess] = {
    val totalRamKb = getTotalRamKb()
    val procEntries = listProcEntries()
    val candidates = procEntries.iterator.filter(_.forall(_.isDigit)).map(_.toInt).filterNot(_ == selfPid).toList

    debug.log(s"Self PID: $selfPid, /proc entries: ${procEntries.size}")

    val withCmdline = candidates.flatMap(pid => readCmdline(pid).map((pid, _)))
    debug.log(s"PIDs with cmdline: ${withCmdline.size}")

    val matched = withCmdline.filter((_, cmdline) => ScalaMonitor.isScalaProcess(cmdline, debug))
    debug.log(s"PIDs passed isScalaProcess: ${matched.size}")

    val withStatus = matched.map { (pid, cmdline) =>
      (pid, cmdline, readStatus(pid))
    }

    val dropped = withStatus.count((_, _, status) => status.isEmpty)
    if dropped > 0 then debug.log(s"PIDs dropped due to missing status: $dropped")

    val results = withStatus.collect {
      case (pid, cmdline, Some(status)) =>
        val rss = statusLong(status, "VmRSS")
        ScalaProcess(
          pid = pid,
          kind = ScalaMonitor.classify(cmdline, debug),
          residentKb = rss,
          virtualKb = statusLong(status, "VmSize"),
          swapKb = Some(statusLong(status, "VmSwap")),
          threads = statusInt(status, "Threads"),
          memPercent = rss.toDouble / totalRamKb * 100.0,
          projectPath = readCwd(pid).map(ScalaMonitor.shortenPath).getOrElse("-")
        )
    }.sortBy(p => -p.residentKb)

    debug.log(s"Discovery summary: ${candidates.size} scanned, ${withCmdline.size} with cmdline, ${matched.size} passed filter, $dropped dropped (status), ${results.size} in output")

    results
  }

  private def getTotalRamKb(): Long =
    readRawPath("/proc/meminfo")
      .flatMap { bytes =>
        val content = new String(bytes, StandardCharsets.UTF_8)
        memTotalPattern.findFirstMatchIn(content).map(_.group(1).toLong)
      }
      .fold {
        debug.log("Failed to read /proc/meminfo or parse MemTotal — using fallback of 1 KB")
        1L
      } { kb =>
        debug.log(s"Total physical memory: $kb KB")
        kb
      }

  private def readRawPath(path: String): Option[Array[Byte]] = Try {
    val is = new BufferedInputStream(new java.io.FileInputStream(path))
    try Iterator.continually(is.read()).takeWhile(_ != -1).map(_.toByte).toArray
    finally is.close()
  }.toOption

  private def readCwd(pid: Int): Option[String] = Try {
    Files.readSymbolicLink(Paths.get(s"/proc/$pid/cwd")).toString
  }.toOption

  private def readProcFile(pid: Int, name: String): Option[Array[Byte]] =
    readProcFile(s"/proc/$pid/$name")

  private def readProcFile(path: String): Option[Array[Byte]] =
    readRawPath(path)

  private def readCmdline(pid: Int): Option[String] =
    readProcFile(pid, "cmdline").map(splitNul)

  private def splitNul(bytes: Array[Byte]): String = {
    @tailrec
    def loop(pos: Int, acc: List[String]): List[String] =
      if (pos >= bytes.length) acc.reverse
      else {
        val end = bytes.indexOf(0.toByte, pos)
        if (end < 0) acc.reverse
        else if (end == pos) loop(end + 1, acc)
        else loop(end + 1, new String(bytes, pos, end - pos, StandardCharsets.UTF_8) :: acc)
      }
    loop(0, Nil).mkString(" ")
  }

  private def readStatus(pid: Int): Option[Map[String, String]] =
    readProcFile(pid, "status").map { bytes =>
      val content = new String(bytes, StandardCharsets.UTF_8)
      content.linesIterator.collect {
        case s"$key:$rest" if key.trim.nonEmpty =>
          key.trim -> rest.trim.stripSuffix(" kB").trim
      }.toMap
    }

  private def listProcEntries(): Array[String] = {
    val stream = Files.newDirectoryStream(Paths.get("/proc"))
    try stream.iterator().asScala.map(_.getFileName.toString).toArray
    finally stream.close()
  }

  private def statusLong(status: Map[String, String], key: String): Long =
    status.get(key).flatMap(_.toLongOption).getOrElse(0L)

  private def statusInt(status: Map[String, String], key: String): Int =
    status.get(key).flatMap(_.toIntOption).getOrElse(0)
}
