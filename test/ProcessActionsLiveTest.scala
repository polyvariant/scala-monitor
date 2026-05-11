package org.polyvariant

import java.nio.file.{Files, Paths}
import scala.scalanative.posix.signal

class ProcessActionsLiveTest extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(60, "s")

  private val testPid = 1

  private def createActions(dumpsDir: String, debug: Debug = Debug.noop): ProcessActionsLive = {
    new ProcessActionsLive(signal, debug, dumpsDir)
  }

  private def withTempDir[A](prefix: String)(f: java.nio.file.Path => A): A = {
    val tmpDir = Files.createTempDirectory(prefix)
    try f(tmpDir)
    finally deleteRecursively(tmpDir)
  }

  test("threadDump creates thread-dumps subdirectory") {
    withTempDir("sm-test") { tmpDir =>
      createActions(tmpDir.toString).threadDump(testPid, "sbt")
      assert(Files.exists(Paths.get(tmpDir.toString, "thread-dumps")),
        "thread-dumps directory should be created")
    }
  }

  test("heapDump creates heap-dumps subdirectory") {
    withTempDir("sm-test") { tmpDir =>
      createActions(tmpDir.toString).heapDump(testPid, "metals")
      assert(Files.exists(Paths.get(tmpDir.toString, "heap-dumps")),
        "heap-dumps directory should be created")
    }
  }

  test("threadDump returns Left for non-JVM PID") {
    withTempDir("sm-test") { tmpDir =>
      val result = createActions(tmpDir.toString).threadDump(testPid, "test")
      assert(result.isLeft, s"Expected Left for non-JVM PID, got: $result")
      result.left.foreach { err =>
        assert(err.msg.contains("1"), s"Error should mention PID, got: ${err.msg}")
      }
    }
  }

  test("heapDump returns Left for non-JVM PID") {
    withTempDir("sm-test") { tmpDir =>
      val result = createActions(tmpDir.toString).heapDump(testPid, "test")
      assert(result.isLeft, s"Expected Left for non-JVM PID, got: $result")
      result.left.foreach { err =>
        assert(err.msg.contains("1"), s"Error should mention PID, got: ${err.msg}")
      }
    }
  }

  test("threadDump logs jcmd invocation via debug") {
    withTempDir("sm-test") { tmpDir =>
      val logged = scala.collection.mutable.ListBuffer.empty[String]
      val recordingDebug = new Debug {
        def log(msg: String): Unit = { logged += msg; () }
      }
      createActions(tmpDir.toString, recordingDebug).threadDump(testPid, "sbt")
      assert(logged.exists(_.contains("jcmd")),
        s"Should log jcmd invocation. Logs: ${logged.mkString("; ")}")
    }
  }

  test("heapDump logs jcmd invocation via debug") {
    withTempDir("sm-test") { tmpDir =>
      val logged = scala.collection.mutable.ListBuffer.empty[String]
      val recordingDebug = new Debug {
        def log(msg: String): Unit = { logged += msg; () }
      }
      createActions(tmpDir.toString, recordingDebug).heapDump(testPid, "metals")
      assert(logged.exists(_.contains("jcmd")),
        s"Should log jcmd invocation. Logs: ${logged.mkString("; ")}")
    }
  }

  test("threadDump creates directory as real directory not file") {
    withTempDir("sm-test") { tmpDir =>
      createActions(tmpDir.toString).threadDump(testPid, "bloop")
      assert(Files.isDirectory(Paths.get(tmpDir.toString, "thread-dumps")),
        "thread-dumps should be a directory, not a file")
    }
  }

  test("heapDump creates directory as real directory not file") {
    withTempDir("sm-test") { tmpDir =>
      createActions(tmpDir.toString).heapDump(testPid, "bloop")
      assert(Files.isDirectory(Paths.get(tmpDir.toString, "heap-dumps")),
        "heap-dumps should be a directory, not a file")
    }
  }

  private def deleteRecursively(path: java.nio.file.Path): Unit = {
    if (Files.isDirectory(path)) {
      Files.list(path).forEach { child =>
        deleteRecursively(child)
      }
    }
    Files.deleteIfExists(path)
    ()
  }

}
