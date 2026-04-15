package org.polyvariant

import mainargs.{main, arg, Flag}
import scala.scalanative.posix.unistd
import scala.scalanative.meta.LinktimeInfo

object ScalaMonitor {

  enum OutputFormat { case Full, Pid }

  object OutputFormat {
    def parse(value: String): OutputFormat = value.toLowerCase match {
      case "pid" => Pid
      case _     => Full
    }
  }

  case class Classification(pattern: String, name: String)

  @main
  def run(
    @arg(short = 'o', doc = "Output format: 'full' (table) or 'pid' (just PIDs)")
    output: String = "full",
    @arg(short = 'f', doc = "Filter processes by key=value (repeatable). Keys: type, project. Use * as wildcard for contains matching, case insensitive")
    filter: Seq[String] = Seq.empty,
    @arg(short = 'd', doc = "Enable verbose debug logging to stderr")
    debug: Flag = Flag(),
    @arg(short = 'w', doc = "Interactive TUI mode (like top)")
    tui: Flag = Flag()
  ): Unit = {
    if (tui.value) {
      TuiApp.run(debug.value)
    } else {
      val processes = discover(debug.value)
      val (filtered, warnings) = applyFilters(processes, filter.toList)
      warnings.foreach(w => System.err.println(s"Warning: $w"))
      if (filtered.isEmpty) println("No Scala-related processes found.")
      else OutputFormat.parse(output) match {
        case OutputFormat.Pid  => filtered.foreach(p => println(p.pid))
        case OutputFormat.Full => print(renderTable(filtered))
      }
    }
  }

  def main(args: Array[String]): Unit =
    mainargs.ParserForMethods(this).runOrExit(args.toIndexedSeq)

  def discover(debug: Boolean): List[ScalaProcess] = {
    val dbg = new Debug(debug)
    val selfPid = unistd.getpid()
    dbg.log(s"Discovering processes (selfPid=$selfPid)")
    val probe: PlatformProbe =
      if (LinktimeInfo.isMac) new MacOsProbe(dbg)
      else if (LinktimeInfo.isLinux) new LinuxProbe(dbg)
      else {
        dbg.log("Unknown platform — neither macOS nor Linux, returning empty")
        return Nil
      }
    probe.discover(selfPid)
  }

  private def renderTable(processes: List[ScalaProcess]): String = {
    val totalRam = processes.map(_.ramKb).sum
    val line = "\u2500" * 130
    val header = "  %-8s %-12s %10s %10s %6s %5s  %-50s".format(
      "PID", "TYPE", "RAM", "SWAP", "MEM%", "THR", "PROJECT"
    )
    val rows = processes.map { p =>
      val swapStr = p.swapKb.map(formatMemory).getOrElse("n/a")
      "  %-8d %-12s %10s %10s %5.1f%% %5d  %-50s".format(
        p.pid, p.kind, formatMemory(p.ramKb),
        swapStr, p.memPercent, p.threads, p.projectPath
      )
    }
    val processWord = if (processes.size == 1) "process" else "processes"
    val top = List(
      line,
      f"  SCALA PROCESS MONITOR  \u2014  ${processes.size} $processWord  \u2014  Total Memory: ${formatMemory(totalRam)}",
      line,
      header,
      line,
    )
    val bottom = List(
      line,
      f"  TOTAL: ${processes.size} processes, ${formatMemory(totalRam)} RAM",
      line,
    )
    (top ++ rows ++ bottom).mkString("\n") + "\n"
  }

  def extractMainClass(cmdline: String): Option[String] = {
    val tokens = cmdline.split("\\s+").drop(1)
    tokens.find { t =>
      t.contains(".") &&
      !t.startsWith("-") &&
      !t.contains("/") &&
      !t.endsWith(".jar") &&
      t.split("\\.").forall(seg => seg.nonEmpty && seg.head.isLetter)
    }
  }

  def classify(cmdline: String, debug: Debug): String = {
    val result = classifications
      .find(c => cmdline.contains(c.pattern))
      .map(_.name)
      .orElse(extractMainClass(cmdline))
      .getOrElse("Scala/JVM")
    debug.log(s"  classify('$cmdline') → '$result'")
    result
  }

  def isScalaProcess(cmdline: String, debug: Debug): Boolean = {
    if (cmdline.isEmpty) return false

    val binary = cmdline.indexOf(' ') match {
      case -1 => cmdline
      case i  => cmdline.substring(0, i)
    }
    val binName = binary.lastIndexOf('/') match {
      case -1 => binary
      case i  => binary.substring(i + 1)
    }

    val branch1Java = binName == "java"
    val branch1Match = if (branch1Java) {
      scalaIndicators.find(ind => cmdline.toLowerCase.contains(ind))
    } else None

    val branch2Match = classifications.find(c => cmdline.contains(c.pattern))

    val accepted = branch1Match.isDefined || branch2Match.isDefined

    if (accepted) {
      debug.log(
        s"  ACCEPT: cmdline='$cmdline' | binName='$binName' | " +
        s"branch1(${branch1Match.map(m => s"'$m'").getOrElse("n/a")}) | " +
        s"branch2(${branch2Match.map(c => s"'${c.pattern}'→'${c.name}'").getOrElse("n/a")})"
      )
    } else if (branch1Java) {
      debug.log(
        s"  REJECT (java, no indicator): cmdline='${cmdline.take(120)}'"
      )
    }

    accepted
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

  def formatMemory(kb: Long): String =
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
