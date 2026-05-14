package org.polyvariant

import scala.cli.build.BuildInfo
import sttp.client4.*
import sttp.client4.curl.CurlBackend
import scala.util.Try
import scala.concurrent.duration._

object Version {

  val current: String = BuildInfo.projectVersion.getOrElse("dev")

  /** Whether the current version is a SNAPSHOT (local build ahead of a tag).
   *  Dynver produces versions like "0.5.5-3-gABC-SNAPSHOT" when commits are ahead of a tag.
   */
  def isSnapshot(v: String): Boolean = v.contains("-")

  /** Extract the base version from a dynver string for semver comparison.
   *  "0.5.5-3-gABC-SNAPSHOT" → "0.5.5"
   *  "0.5.5" → "0.5.5"
   */
  def cleanVersion(v: String): String = {
    val dashIdx = v.indexOf('-')
    if (dashIdx > 0) v.substring(0, dashIdx) else v
  }

  /** Display version: clean version with "+dev" suffix for snapshot builds.
   *  "0.5.5-3-gABC-SNAPSHOT" → "0.5.5+dev"
   *  "0.5.5" → "0.5.5"
   */
  def displayVersion(v: String): String = {
    val clean = cleanVersion(v)
    if (isSnapshot(v)) clean + "+dev" else clean
  }

  def parseSemver(v: String): Option[(Int, Int, Int)] = {
    val stripped = v.stripPrefix("v")
    stripped.split("\\.") match {
      case Array(maj, min, pat) =>
        Try((maj.toInt, min.toInt, pat.toInt)).toOption
      case _ => None
    }
  }

  def compareSemver(a: (Int, Int, Int), b: (Int, Int, Int)): Int = {
    if (a._1 != b._1) a._1.compareTo(b._1)
    else if (a._2 != b._2) a._2.compareTo(b._2)
    else a._3.compareTo(b._3)
  }

  def fetchLatestReleaseVersion(): Option[String] = {
    Try {
      val backend = CurlBackend()
      try {
        val response = basicRequest
          .get(uri"https://github.com/polyvariant/scala-monitor/releases/latest")
          .followRedirects(false)
          .readTimeout(5.seconds)
          .send(backend)

        if (response.code.isRedirect) {
          response.headers("Location").headOption.flatMap { location =>
            location.trim.split("/").lastOption.map(_.stripPrefix("v")).filter(_.nonEmpty)
          }
        } else {
          None
        }
      } finally {
        backend.close()
      }
    }.toOption.flatten
  }

  sealed trait VersionStatus
  case object UpToDate extends VersionStatus
  case class UpdateAvailable(latestVersion: String) extends VersionStatus
  case object VersionCheckFailed extends VersionStatus
  case object VersionCheckPending extends VersionStatus

  def checkForUpdate(): VersionStatus = {
    val cleanCurrent = cleanVersion(current)
    (parseSemver(cleanCurrent), fetchLatestReleaseVersion().flatMap(parseSemver)) match {
      case (Some(cur), Some(latest)) =>
        if (compareSemver(cur, latest) >= 0) UpToDate
        else UpdateAvailable(s"${latest._1}.${latest._2}.${latest._3}")
      case _ => VersionCheckFailed
    }
  }
}
