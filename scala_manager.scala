//> using scala 3.8.3
//> using platform native
//> using nativeVersion 0.5.10
//> using nativeMode release-fast
//> using nativeLto thin
//> using option -no-indent

import java.io.{BufferedInputStream, FileInputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.scalanative.posix.unistd
import scala.util.Try

case class ScalaProcess(
  pid: Int,
  kind: String,
  residentKb: Long,
  virtualKb: Long,
  swapKb: Long,
  threads: Int,
  memPercent: Double,
  projectPath: String
)

object ScalaManager {

  private val classifications: Vector[(String, String)] = Vector(
    "scala.meta.metals.Main"        -> "Metals",
    "bloop.BloopServer"             -> "Bloop",
    "bloop.engine.BuildServer"      -> "Bloop",
    "bloop.cli"                     -> "Bloop",
    "xsbt.boot.Boot"                -> "sbt",
    "sbt-launch"                    -> "sbt",
    "sbt.launch"                    -> "sbt",
    "scala.tools.nsc.Main"          -> "scalac",
    "scala.tools.nsc.MainGenericRunner" -> "scala",
    "org.scalafmt.Main"             -> "scalafmt",
    "org.scalafmt.cli"              -> "scalafmt",
    "org.scalafix.Main"             -> "scalafix",
    "org.scalafix.cli"              -> "scalafix",
    "scala.cli.ScalaCli"            -> "scala-cli",
    "scala.cli.launcher"            -> "scala-cli",
    "coursier.launcher.Main"        -> "coursier",
    "io.getcoursier.cli.launcher"   -> "coursier",
    "mill.main.Main"                -> "mill",
    "mill.Main"                     -> "mill",
  )

  private val scalaIndicators: Vector[String] = Vector(
    "metals", "bloop", "sbt", "scala", "coursier", "mill",
    "scala-cli", "scalac", "scalafmt", "scalafix", "zinc",
    "scala-compiler", "scala-library", "scala-reflect",
    "scala3-library", "scala3-compiler", "bsp", "mtags",
    "semanticdb", "scalameta"
  )

  private val memTotalPattern = raw"""MemTotal:\s+(\d+)\s+kB""".r

  private def statusLong(status: Map[String, String], key: String): Long =
    status.get(key).flatMap(_.toLongOption).getOrElse(0L)

  private def statusInt(status: Map[String, String], key: String): Int =
    status.get(key).flatMap(_.toIntOption).getOrElse(0)

  private def classify(cmdline: String): String =
    classifications
      .find((pattern, _) => cmdline.contains(pattern))
      .map((_, name) => name)
      .getOrElse("Scala/JVM")

  private def isScalaProcess(cmdline: String): Boolean =
    scalaIndicators.exists(cmdline.toLowerCase.contains)

  private def readProcFile(pid: Int, name: String): Option[Array[Byte]] = Try {
    val is = new BufferedInputStream(new FileInputStream(s"/proc/$pid/$name"))
    try {
      val buf = scala.collection.mutable.ArrayBuffer.newBuilder[Byte]
      val chunk = Array.ofDim[Byte](4096)
      var n = is.read(chunk)
      while (n > 0) {
        var i = 0
        while (i < n) { buf += chunk(i); i += 1 }
        n = is.read(chunk)
      }
      buf.result().toArray
    } finally is.close()
  }.toOption

  private def readCmdline(pid: Int): Option[String] =
    readProcFile(pid, "cmdline").map { bytes =>
      val parts = scala.collection.mutable.ListBuffer.empty[String]
      var start = 0
      var i = 0
      while (i < bytes.length) {
        if (bytes(i) == 0) {
          if (i > start) {
            parts += new String(bytes, start, i - start, StandardCharsets.UTF_8)
          }
          start = i + 1
        }
        i += 1
      }
      parts.mkString(" ")
    }

  private def readCwd(pid: Int): Option[String] = Try {
    val target = Files.readSymbolicLink(Paths.get(s"/proc/$pid/cwd")).toString
    shortenPath(target)
  }.toOption

  private def readStatus(pid: Int): Option[Map[String, String]] =
    readProcFile(pid, "status").map { bytes =>
      val content = new String(bytes, StandardCharsets.UTF_8)
      content.linesIterator.collect {
        case line if line.indexOf(':') > 0 =>
          val idx = line.indexOf(':')
          line.substring(0, idx).trim -> line.substring(idx + 1).trim.stripSuffix(" kB").trim
      }.toMap
    }

  private def getTotalRamKb(): Long = Try {
    val is = new BufferedInputStream(new FileInputStream("/proc/meminfo"))
    try {
      val buf = scala.collection.mutable.ArrayBuffer.newBuilder[Byte]
      val chunk = Array.ofDim[Byte](4096)
      var n = is.read(chunk)
      while (n > 0) {
        var i = 0
        while (i < n) { buf += chunk(i); i += 1 }
        n = is.read(chunk)
      }
      val content = new String(buf.result().toArray, StandardCharsets.UTF_8)
      memTotalPattern.findFirstMatchIn(content).map(_.group(1).toLong).getOrElse(1L)
    } finally is.close()
  }.getOrElse(1L)

  private def formatMemory(kb: Long): String =
    if (kb >= 1024L * 1024L) f"${kb.toDouble / (1024.0 * 1024.0)}%.1f GB"
    else if (kb >= 1024L) f"${kb.toDouble / 1024.0}%.0f MB"
    else s"$kb kB"

  private def shortenPath(path: String): String = {
    val home = System.getProperty("user.home")
    if (path.startsWith(home)) "~" + path.substring(home.length) else path
  }

  private def listProcEntries(): Array[String] = {
    val entries = scala.collection.mutable.ArrayBuffer[String]()
    val stream = Files.newDirectoryStream(Paths.get("/proc"))
    val iter = stream.iterator()
    while (iter.hasNext) {
      entries += iter.next().getFileName.toString
    }
    stream.close()
    entries.toArray
  }

  def discover(): List[ScalaProcess] = {
    val totalRamKb = getTotalRamKb()
    val selfPid = unistd.getpid()

    listProcEntries().iterator
      .filter(_.forall(_.isDigit))
      .map(_.toInt)
      .filterNot(_ == selfPid)
      .flatMap { pid =>
        for {
          cmdline <- readCmdline(pid)
          if isScalaProcess(cmdline)
          status <- readStatus(pid)
        } yield ScalaProcess(
          pid = pid,
          kind = classify(cmdline),
          residentKb = statusLong(status, "VmRSS"),
          virtualKb = statusLong(status, "VmSize"),
          swapKb = statusLong(status, "VmSwap"),
          threads = statusInt(status, "Threads"),
          memPercent = statusLong(status, "VmRSS").toDouble / totalRamKb * 100.0,
          projectPath = readCwd(pid).getOrElse("-")
        )
      }
      .toList
      .sortBy(p => -p.residentKb)
  }

  def renderTable(processes: List[ScalaProcess]): String = {
    val totalResident = processes.map(_.residentKb).sum
    val totalVirtual = processes.map(_.virtualKb).sum
    val sb = new StringBuilder()
    val line = "─" * 130
    val header = "  %-8s %-12s %10s %12s %10s %6s %5s  %-50s".format(
      "PID", "TYPE", "RSS", "VSZ", "SWAP", "MEM%", "THR", "PROJECT"
    )

    sb.append(line).append('\n')
    sb.append(f"  SCALA PROCESS MONITOR  —  ${processes.size} process${if (processes.size == 1) "" else "es"}")
    sb.append(f"  —  Total Memory: ${formatMemory(totalResident)}").append('\n')
    sb.append(line).append('\n')
    sb.append(header).append('\n')
    sb.append(line).append('\n')

    processes.foreach { p =>
      sb.append("  %-8d %-12s %10s %12s %10s %5.1f%% %5d  %-50s".format(
        p.pid, p.kind, formatMemory(p.residentKb), formatMemory(p.virtualKb),
        formatMemory(p.swapKb), p.memPercent, p.threads, p.projectPath
      )).append('\n')
    }

    sb.append(line).append('\n')
    sb.append(f"  TOTAL: ${processes.size} processes, ${formatMemory(totalResident)} RSS, ${formatMemory(totalVirtual)} VSZ").append('\n')
    sb.append(line).append('\n')
    sb.toString()
  }
}

@main def run(): Unit = {
  val processes = ScalaManager.discover()
  if (processes.isEmpty) println("No Scala-related processes found.")
  else print(ScalaManager.renderTable(processes))
}
