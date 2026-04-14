package org.polyvariant

class PsOutputParsingSuite extends munit.FunSuite {

  val psOutputLines: List[String] = {
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

  test("detects mill daemon but classifies it as Scala/JVM (missing classification for MillDaemonMain)") {
    val results = MacOsProbe.parsePsLines(psOutputLines, selfPid = -1, debug = new Debug(false), cwdResolver = noCwd)
    val mill = results.find(_.pid == 2983)
    assert(mill.isDefined, s"Expected mill daemon (PID 2983) to be detected but got: ${results.map(_.kind)}")
    assertEquals(mill.get.memPercent, 0.2)
    assertEquals(mill.get.ramKb, 75664L)
    assertEquals(mill.get.kind, "Scala/JVM", "mill.daemon.MillDaemonMain is not in the classification list, so it falls through to Scala/JVM")
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
    assert(kinds.contains("Scala/JVM"), "Mill daemon and user app should fall through to Scala/JVM")
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

}
