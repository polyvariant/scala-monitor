package org.polyvariant

class PsOutputParsingSuite extends munit.FunSuite {

  lazy val psOutputLines: List[String] = {
    val source = scala.io.Source.fromFile("test/ps-output.txt")
    try source.getLines().toList
    finally source.close()
  }

  val noCwd: Int => Option[String] = _ => None

  test("detects bloop server from real macOS ps output") {
    val results = MacOsProbe.parsePsLines(psOutputLines, selfPid = -1, debug = new Debug(false), cwdResolver = noCwd)
    val bloop = results.find(_.kind == "Bloop")
    assert(bloop.isDefined, s"Expected Bloop process but got kinds: ${results.map(_.kind)}")
    assertEquals(bloop.get.pid, 1531)
    assertEquals(bloop.get.memPercent, 0.5)
    assertEquals(bloop.get.ramKb, 184288L)
  }

  test("detects mill daemon and classifies it by main class") {
    val results = MacOsProbe.parsePsLines(psOutputLines, selfPid = -1, debug = new Debug(false), cwdResolver = noCwd)
    val mill = results.find(_.pid == 2983)
    assert(mill.isDefined, s"Expected mill daemon (PID 2983) to be detected but got: ${results.map(_.kind)}")
    assertEquals(mill.get.memPercent, 0.2)
    assertEquals(mill.get.ramKb, 75664L)
    assertEquals(mill.get.kind, "mill.daemon.MillDaemonMain", "mill.daemon.MillDaemonMain is not in the classification list, so extractMainClass is used")
  }

  test("does not detect mill native launcher (no scala indicators in binary name)") {
    val results = MacOsProbe.parsePsLines(psOutputLines, selfPid = -1, debug = new Debug(false), cwdResolver = noCwd)
    val millNative = results.find(_.pid == 2974)
    assert(millNative.isEmpty, s"Mill native launcher (PID 2974) should not be detected as Scala process, but got: $millNative")
  }

  test("detects exactly 3 Scala processes from real ps output (bloop + mill daemon + user app)") {
    val results = MacOsProbe.parsePsLines(psOutputLines, selfPid = -1, debug = new Debug(false), cwdResolver = noCwd)
    assertEquals(results.size, 3, s"Expected exactly 3 Scala processes, got ${results.size} with kinds: ${results.map(_.kind)}")
    val kinds = results.map(_.kind).toSet
    assert(kinds.contains("Bloop"), "Bloop server should be classified as Bloop")
    assert(kinds.contains("mill.daemon.MillDaemonMain"), "Mill daemon should be classified by main class")
    assert(kinds.contains("com.example.myapp.Main"), "User app should be classified by main class")
  }

  test("does not detect non-JVM processes like zsh, docker, launchd") {
    val results = MacOsProbe.parsePsLines(psOutputLines, selfPid = -1, debug = new Debug(false), cwdResolver = noCwd)
    val pids = results.map(_.pid).toSet
    assert(!pids.contains(1), "launchd should not be detected")
    assert(!pids.contains(4701), "ps command should not be detected")
    assert(!pids.contains(2435), "zsh should not be detected")
    assert(!pids.contains(95996), "docker-compose should not be detected")
  }

  test("parses macOS ps lines with comma decimal separator") {
    val lines = List(" 1531  0,5 184288 /nix/store/zulu/bin/java bloop.BloopServer")
    val results = MacOsProbe.parsePsLines(lines, selfPid = -1, debug = new Debug(false), cwdResolver = noCwd)
    assertEquals(results.size, 1)
    assertEquals(results.head.memPercent, 0.5)
  }

  test("parses macOS ps lines with dot decimal separator") {
    val lines = List(" 1531  0.5 184288 /nix/store/zulu/bin/java bloop.BloopServer")
    val results = MacOsProbe.parsePsLines(lines, selfPid = -1, debug = new Debug(false), cwdResolver = noCwd)
    assertEquals(results.size, 1)
    assertEquals(results.head.memPercent, 0.5)
  }

  test("excludes process matching selfPid") {
    val lines = List(" 1531  0,5 184288 /nix/store/zulu/bin/java bloop.BloopServer")
    val results = MacOsProbe.parsePsLines(lines, selfPid = 1531, debug = new Debug(false), cwdResolver = noCwd)
    assert(results.isEmpty, "Process matching selfPid should be excluded")
  }

  test("returns empty list for empty input") {
    val results = MacOsProbe.parsePsLines(Nil, selfPid = -1, debug = new Debug(false), cwdResolver = noCwd)
    assertEquals(results.size, 0)
  }

  test("skips malformed lines (too few fields)") {
    val lines = List(" 1531  0,5", "badline", " 2000  0.3  65536 /usr/bin/java -cp scala-library.jar scala.tools.nsc.Main")
    val results = MacOsProbe.parsePsLines(lines, selfPid = -1, debug = new Debug(false), cwdResolver = noCwd)
    assertEquals(results.size, 1)
    assertEquals(results.head.pid, 2000)
  }

  test("classifies Metals and sbt from cmdline patterns") {
    val lines = List(
      " 1001  1.0 100000 /usr/bin/java scala.meta.metals.Main",
      " 1002  2.0 200000 /usr/bin/java xsbt.boot.Boot project"
    )
    val results = MacOsProbe.parsePsLines(lines, selfPid = -1, debug = new Debug(false), cwdResolver = noCwd)
    assertEquals(results.size, 2)
    assertEquals(results.find(_.pid == 1001).get.kind, "Metals")
    assertEquals(results.find(_.pid == 1002).get.kind, "sbt")
  }

  test("extractMainClass finds FQN from java cmdline") {
    assertEquals(
      ScalaMonitor.extractMainClass("java -Xmx2g -cp app.jar com.example.myapp.Server"),
      Some("com.example.myapp.Server")
    )
  }

  test("extractMainClass returns None when no FQN present") {
    assertEquals(
      ScalaMonitor.extractMainClass("java -Xmx2g -cp app.jar"),
      None
    )
  }

  test("extractMainClass rejects flags and paths") {
    assertEquals(
      ScalaMonitor.extractMainClass("java -Dfoo=bar -Xmx2g"),
      None
    )
  }

  test("classify falls back to main class before Scala/JVM") {
    assertEquals(
      ScalaMonitor.classify("java -cp app.jar com.example.myapp.Main", new Debug(false)),
      "com.example.myapp.Main"
    )
  }

  test("classify still returns Scala/JVM when no main class found") {
    assertEquals(
      ScalaMonitor.classify("java -Xmx2g", new Debug(false)),
      "Scala/JVM"
    )
  }

  test("classify prefers known pattern over main class extraction") {
    assertEquals(
      ScalaMonitor.classify("java -cp app.jar bloop.BloopServer", new Debug(false)),
      "Bloop"
    )
  }

}
