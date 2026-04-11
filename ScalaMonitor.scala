import mainargs.{main, arg, ParserForMethods}
import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.annotation.tailrec
import scala.scalanative.posix.unistd
import scala.scalanative.meta.LinktimeInfo
import scala.util.Try

object ScalaMonitor {

  enum OutputFormat { case Full, Pid }

  object OutputFormat {
    def parse(value: String): OutputFormat = value.toLowerCase match {
      case "pid" => Pid
      case _     => Full
    }
  }

  case class Classification(pattern: String, name: String)

  case class ScalaProcess(
    pid: Int,
    kind: String,
    residentKb: Long,
    virtualKb: Long,
    swapKb: Option[Long],
    threads: Int,
    memPercent: Double,
    projectPath: String
  )

  @main
  def run(
    @arg(short = 'o', doc = "Output format: 'full' (table) or 'pid' (just PIDs)")
    output: String = "full",
    @arg(short = 'f', doc = "Filter processes by key=value (repeatable). Keys: type, project. Use * as wildcard for contains matching, case insensitive")
    filter: Seq[String] = Seq.empty
  ): Unit = {
    val processes = discover()
    val (filtered, warnings) = applyFilters(processes, filter.toList)
    warnings.foreach(w => System.err.println(s"Warning: $w"))
    if (filtered.isEmpty) println("No Scala-related processes found.")
    else OutputFormat.parse(output) match {
      case OutputFormat.Pid  => filtered.foreach(p => println(p.pid))
      case OutputFormat.Full => print(renderTable(filtered))
    }
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)

  def discover(): List[ScalaProcess] = {
    val selfPid = unistd.getpid()
    if (LinktimeInfo.isMac) {
      val totalRamKb = MacOsProbe.getTotalRamKb()
      MacOsProbe.discoverMac(totalRamKb, selfPid)
    } else if (LinktimeInfo.isLinux) {
      discoverLinux(selfPid)
    } else Nil
  }

  private def discoverLinux(selfPid: Int): List[ScalaProcess] = {
    val totalRamKb = getTotalRamKb()
    listProcEntries().iterator
      .filter(_.forall(_.isDigit))
      .map(_.toInt)
      .filterNot(_ == selfPid)
      .flatMap { pid =>
        for {
          cmdline <- readCmdline(pid)
          if isScalaProcess(cmdline)
          status  <- readStatus(pid)
        } yield {
          val rss = statusLong(status, "VmRSS")
          ScalaProcess(
            pid = pid,
            kind = classify(cmdline),
            residentKb = rss,
            virtualKb = statusLong(status, "VmSize"),
            swapKb = Some(statusLong(status, "VmSwap")),
            threads = statusInt(status, "Threads"),
            memPercent = rss.toDouble / totalRamKb * 100.0,
            projectPath = readCwd(pid).map(shortenPath).getOrElse("-")
          )
        }
      }
      .toList
      .sortBy(p => -p.residentKb)
  }

  private def renderTable(processes: List[ScalaProcess]): String = {
    val totalResident = processes.map(_.residentKb).sum
    val totalVirtual  = processes.map(_.virtualKb).sum
    val line = "\u2500" * 130
    val header = "  %-8s %-12s %10s %12s %10s %6s %5s  %-50s".format(
      "PID", "TYPE", "RSS", "VSZ", "SWAP", "MEM%", "THR", "PROJECT"
    )
    val rows = processes.map { p =>
      val swapStr = p.swapKb.map(formatMemory).getOrElse("n/a")
      "  %-8d %-12s %10s %12s %10s %5.1f%% %5d  %-50s".format(
        p.pid, p.kind, formatMemory(p.residentKb), formatMemory(p.virtualKb),
        swapStr, p.memPercent, p.threads, p.projectPath
      )
    }
    val processWord = if (processes.size == 1) "process" else "processes"
    val top = List(
      line,
      f"  SCALA PROCESS MONITOR  \u2014  ${processes.size} $processWord  \u2014  Total Memory: ${formatMemory(totalResident)}",
      line,
      header,
      line,
    )
    val bottom = List(
      line,
      f"  TOTAL: ${processes.size} processes, ${formatMemory(totalResident)} RSS, ${formatMemory(totalVirtual)} VSZ",
      line,
    )
    (top ++ rows ++ bottom).mkString("\n") + "\n"
  }

  def classify(cmdline: String): String =
    classifications
      .find(c => cmdline.contains(c.pattern))
      .map(_.name)
      .getOrElse("Scala/JVM")

  def isScalaProcess(cmdline: String): Boolean = {
    val binary = cmdline.indexOf(' ') match {
      case -1 => cmdline
      case i  => cmdline.substring(0, i)
    }
    val binName = binary.lastIndexOf('/') match {
      case -1 => binary
      case i  => binary.substring(i + 1)
    }
    binName == "java" && scalaIndicators.exists(cmdline.toLowerCase.contains) ||
      classifications.exists(c => cmdline.contains(c.pattern))
  }

  def shortenPath(path: String): String =
    if (path.startsWith(homeDir)) "~" + path.substring(homeDir.length) else path

  private lazy val homeDir: String = System.getProperty("user.home")

  private def applyFilters(processes: List[ScalaProcess], filters: List[String]): (List[ScalaProcess], List[String]) =
    filters.foldLeft((processes, List.empty[String])) { case ((acc, warnings), filter) =>
      filter.split("=", 2) match {
        case Array("type", value) =>
          (acc.filter(p => matchesGlob(p.kind, value)), warnings)
        case Array("project", value) =>
          (acc.filter(p => matchesGlob(p.projectPath, value)), warnings)
        case _ =>
          (acc, warnings :+ s"Unknown filter: $filter")
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

  private def readRawPath(path: String): Option[Array[Byte]] = Try {
    val is = new BufferedInputStream(new java.io.FileInputStream(path))
    try Iterator.continually(is.read()).takeWhile(_ != -1).map(_.toByte).toArray
    finally is.close()
  }.toOption

  private def readCwd(pid: Int): Option[String] = Try {
    Files.readSymbolicLink(Paths.get(s"/proc/$pid/cwd")).toString
  }.toOption

  private def readProcFile(pid: Int, name: String): Option[Array[Byte]] =
    readRawPath(s"/proc/$pid/$name")

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

  private def formatMemory(kb: Long): String =
    if (kb >= 1024L * 1024L) f"${kb.toDouble / (1024.0 * 1024.0)}%.1f GB"
    else if (kb >= 1024L) f"${kb.toDouble / 1024.0}%.0f MB"
    else s"$kb kB"

  private def matchesGlob(text: String, pattern: String): Boolean = {
    val t = text.toLowerCase
    val trimmed = pattern.toLowerCase.trim
    val starts = trimmed.startsWith("*")
    val ends   = trimmed.endsWith("*")
    val core   = trimmed.stripPrefix("*").stripSuffix("*")
    if (starts && ends) t.contains(core)
    else if (starts) t.endsWith(core)
    else if (ends) t.startsWith(core)
    else t == core
  }

  private def statusLong(status: Map[String, String], key: String): Long =
    status.get(key).flatMap(_.toLongOption).getOrElse(0L)

  private def statusInt(status: Map[String, String], key: String): Int =
    status.get(key).flatMap(_.toIntOption).getOrElse(0)

  private val classifications: List[Classification] = List(
    Classification("scala.meta.metals.Main",        "Metals"),
    Classification("bloop.BloopServer",             "Bloop"),
    Classification("bloop.engine.BuildServer",      "Bloop"),
    Classification("bloop.cli",                     "Bloop"),
    Classification("xsbt.boot.Boot",                "sbt"),
    Classification("sbt-launch",                    "sbt"),
    Classification("sbt.launch",                    "sbt"),
    Classification("scala.tools.nsc.Main",          "scalac"),
    Classification("scala.tools.nsc.MainGenericRunner", "scala"),
    Classification("org.scalafmt.Main",             "scalafmt"),
    Classification("org.scalafmt.cli",              "scalafmt"),
    Classification("org.scalafix.Main",             "scalafix"),
    Classification("org.scalafix.cli",              "scalafix"),
    Classification("scala.cli.ScalaCli",            "scala-cli"),
    Classification("scala.cli.launcher",            "scala-cli"),
    Classification("coursier.launcher.Main",        "coursier"),
    Classification("io.getcoursier.cli.launcher",   "coursier"),
    Classification("mill.main.Main",                "mill"),
    Classification("mill.Main",                     "mill"),
  )

  private val scalaIndicators: List[String] = List(
    "metals", "bloop", "sbt", "scala", "coursier", "mill",
    "scala-cli", "scalac", "scalafmt", "scalafix", "zinc",
    "scala-compiler", "scala-library", "scala-reflect",
    "scala3-library", "scala3-compiler", "bsp", "mtags",
    "semanticdb", "scalameta"
  )

}
