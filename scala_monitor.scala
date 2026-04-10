//> using scala 3.8.3
//> using platform native
//> using nativeVersion 0.5.10
//> using nativeMode release-fast
//> using nativeLto thin
//> using option -no-indent
//> using dep com.lihaoyi::mainargs_native0.5:0.7.8

import java.io.{BufferedInputStream, File, FileInputStream}
import mainargs.{main, arg, ParserForMethods}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.scalanative.posix.unistd
import scala.util.Try

object ScalaManager {

  @main
  def run(
    @arg(short = 'o', doc = "Output format: 'full' (table) or 'pid' (just PIDs)")
    output: String = "full",
    @arg(short = 'f', doc = "Filter processes by key=value (repeatable). Keys: type, project. Use * as wildcard for contains matching, case insensitive")
    filter: Seq[String] = Seq.empty
  ): Unit = {
    val processes = discover()
    val filtered = applyFilters(processes, filter.toList)
    if (filtered.isEmpty) println("No Scala-related processes found.")
    else output.toLowerCase match {
      case "pid"  => filtered.foreach(p => println(p.pid))
      case _      => print(renderTable(filtered))
    }
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)

  private def discover(): List[ScalaProcess] = {
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
          projectPath = readCwd(pid).map(shortenPath).getOrElse("-")
        )
      }
      .toList
      .sortBy(p => -p.residentKb)
  }

  private def renderTable(processes: List[ScalaProcess]): String = {
    val totalResident = processes.map(_.residentKb).sum
    val totalVirtual = processes.map(_.virtualKb).sum
    val sb = new StringBuilder()
    val line = "─" * 130
    val header = "  %-8s %-12s %10s %12s %10s %6s %5s  %-50s".format(
      "PID", "TYPE", "RSS", "VSZ", "SWAP", "MEM%", "THR", "PROJECT"
    )

    sb.append(line).append('\n')
    sb.append(f"  SCALA PROCESS MONITOR").append('\n')
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

  private def classify(cmdline: String): String =
    classifications
      .find((pattern, _) => cmdline.contains(pattern))
      .map((_, name) => name)
      .getOrElse("Scala/JVM")

  private def isScalaProcess(cmdline: String): Boolean = {
    val binary = cmdline.indexOf(' ') match {
      case -1 => cmdline
      case i  => cmdline.substring(0, i)
    }
    val binName = binary.lastIndexOf('/') match {
      case -1 => binary
      case i  => binary.substring(i + 1)
    }
    binName == "java" && scalaIndicators.exists(cmdline.toLowerCase.contains) ||
      classifications.exists((pattern, _) => cmdline.contains(pattern))
  }

  private def applyFilters(processes: List[ScalaProcess], filters: List[String]): List[ScalaProcess] =
    filters.foldLeft(processes) { case (acc, filter) =>
      filter.split("=", 2) match {
        case Array("type", value) =>
          acc.filter(p => matchesGlob(p.kind, value))
        case Array("project", value) =>
          acc.filter(p => matchesGlob(p.projectPath, value))
        case _ =>
          println(s"Unknown filter: $filter"); acc
      }
    }

  private val memTotalPattern = raw"""MemTotal:\s+(\d+)\s+kB""".r

  private def getTotalRamKb(): Long =
    readRawPath("/proc/meminfo")
      .flatMap { bytes =>
        val content = new String(bytes, StandardCharsets.UTF_8)
        memTotalPattern.findFirstMatchIn(content).map(_.group(1).toLong)
      }
      .getOrElse(1L)

  private def readCwd(pid: Int): Option[String] = Try {
    Files.readSymbolicLink(Paths.get(s"/proc/$pid/cwd")).toString
  }.toOption

  private def readRawPath(path: String): Option[Array[Byte]] = Try {
    val chunk = Array.ofDim[Byte](4096)
    val is = new BufferedInputStream(new FileInputStream(path))
    try Iterator.continually(is.read(chunk)).takeWhile(_ > 0).flatMap(chunk.take).toArray
    finally is.close()
  }.toOption

  private def readProcFile(pid: Int, name: String): Option[Array[Byte]] =
    readRawPath(s"/proc/$pid/$name")

  private def readCmdline(pid: Int): Option[String] =
    readProcFile(pid, "cmdline").map { bytes =>
      new String(bytes, 0, bytes.length, StandardCharsets.UTF_8)
        .split('\u0000')
        .filter(_.nonEmpty)
        .mkString(" ")
    }

  private def readStatus(pid: Int): Option[Map[String, String]] =
    readProcFile(pid, "status").map { bytes =>
      val content = new String(bytes, StandardCharsets.UTF_8)
      content.linesIterator.collect {
        case line if line.indexOf(':') > 0 =>
          val idx = line.indexOf(':')
          line.substring(0, idx).trim -> line.substring(idx + 1).trim.stripSuffix(" kB").trim
      }.toMap
    }

  private def listProcEntries(): Array[String] =
    Option(new File("/proc").listFiles()).map(_.map(_.getName)).getOrElse(Array.empty)

  private def formatMemory(kb: Long): String =
    if (kb >= 1024L * 1024L) f"${kb.toDouble / (1024.0 * 1024.0)}%.1f GB"
    else if (kb >= 1024L) f"${kb.toDouble / 1024.0}%.0f MB"
    else s"$kb kB"

  private def shortenPath(path: String): String = {
    val home = System.getProperty("user.home")
    if (path.startsWith(home)) "~" + path.substring(home.length) else path
  }

  private def matchesGlob(text: String, pattern: String): Boolean = {
    val t = text.toLowerCase
    val p = pattern.toLowerCase.trim.stripPrefix("*").stripSuffix("*")
    if (pattern.startsWith("*") && pattern.endsWith("*")) t.contains(p)
    else if (pattern.startsWith("*")) t.endsWith(p)
    else if (pattern.endsWith("*")) t.startsWith(p)
    else t == p
  }

  private def statusLong(status: Map[String, String], key: String): Long =
    status.get(key).flatMap(_.toLongOption).getOrElse(0L)

  private def statusInt(status: Map[String, String], key: String): Int =
    status.get(key).flatMap(_.toIntOption).getOrElse(0)

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

  private case class ScalaProcess(
    pid: Int,
    kind: String,
    residentKb: Long,
    virtualKb: Long,
    swapKb: Long,
    threads: Int,
    memPercent: Double,
    projectPath: String
  )

}
