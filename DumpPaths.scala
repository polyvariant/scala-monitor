package org.polyvariant

object DumpPaths {

  def dumpFileName(dumpType: String, pid: Int, kind: String, extension: String): String = {
    val timestamp = formatTimestamp(System.currentTimeMillis())
    val kindPart = if (kind.nonEmpty) s"-$kind" else ""
    s"${dumpType}-dump-$pid$kindPart-$timestamp.$extension"
  }

  private val MonthDays: List[Int] = List(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

  private def formatTimestamp(epochMillis: Long): String = {
    val totalSeconds = epochMillis / 1000
    val secsOfDay = (totalSeconds % 86400L).toInt
    val totalDays = (totalSeconds / 86400L).toInt
    val (year, dayInYear) = splitYearDay(totalDays, 1970)
    val (month, day) = splitMonthDay(year, dayInYear)
    val hour = secsOfDay / 3600
    val minute = (secsOfDay % 3600) / 60
    val second = secsOfDay % 60
    f"$year-$month%02d-$day%02d" + "T" + f"$hour%02d-$minute%02d-$second%02d"
  }

  private def splitYearDay(remainingDays: Int, candidateYear: Int): (Int, Int) = {
    val dy = if (isLeap(candidateYear)) 366 else 365
    if (remainingDays < dy) (candidateYear, remainingDays)
    else splitYearDay(remainingDays - dy, candidateYear + 1)
  }

  private def splitMonthDay(year: Int, dayInYear0: Int): (Int, Int) = {
    val dayInYear = dayInYear0 + 1
    splitMonthDayAcc(year, dayInYear, 1)
  }

  private def splitMonthDayAcc(year: Int, remainingDays: Int, month: Int): (Int, Int) = {
    val dm = daysInMonth(year, month)
    if (remainingDays <= dm) (month, remainingDays)
    else splitMonthDayAcc(year, remainingDays - dm, month + 1)
  }

  private def daysInMonth(year: Int, month: Int): Int = {
    if (month == 2 && isLeap(year)) 29 else MonthDays(month - 1)
  }

  private def isLeap(year: Int): Boolean = {
    (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
  }

  def resolveDumpsDir(overridePath: Option[String]): String =
    resolveDumpsDirWithEnv(overridePath, Option(System.getenv("XDG_CACHE_HOME")))

  def resolveDumpsDirWithEnv(overridePath: Option[String], xdgCacheHome: Option[String]): String = {
    overridePath.getOrElse {
      val cacheBase = xdgCacheHome.filter(_.nonEmpty)
        .getOrElse(System.getProperty("user.home") + "/.cache")
      s"$cacheBase/scala-monitor/dumps"
    }
  }

}
