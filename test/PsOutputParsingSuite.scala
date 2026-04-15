package org.polyvariant

import java.io.FileOutputStream

class PsOutputParsingSuite extends munit.FunSuite {

  lazy val psOutputLines: List[String] = {
    val source = scala.io.Source.fromFile("test/ps-output.txt")
    try source.getLines().toList
    finally source.close()
  }

  val noCwd: Int => Option[String] = _ => None

  // ---------------------------------------------------------------------------
  // Minimal stored-ZIP helper used by the -jar tests.
  // Writes a single STORED (uncompressed) entry into a ZIP file.
  // ---------------------------------------------------------------------------
  private def writeStoredZipEntry(
    fos: FileOutputStream,
    entryName: String,
    entryContent: Array[Byte],
  ): (Int, Int) = { // returns (localHeaderOffset, totalBytesWritten)
    def le2(v: Int): Array[Byte] = Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte)
    def le4(v: Int): Array[Byte] = Array(
      (v & 0xff).toByte, ((v >> 8) & 0xff).toByte,
      ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte,
    )
    val fnBytes = entryName.getBytes("UTF-8")
    val localHeaderOffset = 0
    // Local file header
    fos.write(Array(0x50, 0x4b, 0x03, 0x04).map(_.toByte)) // signature
    fos.write(le2(20))                    // version needed
    fos.write(le2(0))                     // flags
    fos.write(le2(0))                     // compression: stored
    fos.write(le2(0)); fos.write(le2(0))  // mod time, mod date
    fos.write(le4(0))                     // CRC-32 (not checked by our reader)
    fos.write(le4(entryContent.length))   // compressed size
    fos.write(le4(entryContent.length))   // uncompressed size
    fos.write(le2(fnBytes.length))        // filename length
    fos.write(le2(0))                     // extra field length
    fos.write(fnBytes)
    fos.write(entryContent)
    val localEntrySize = 30 + fnBytes.length + entryContent.length
    // Central directory entry
    fos.write(Array(0x50, 0x4b, 0x01, 0x02).map(_.toByte))
    fos.write(le2(20)); fos.write(le2(20))           // versions
    fos.write(le2(0)); fos.write(le2(0))             // flags, compression
    fos.write(le2(0)); fos.write(le2(0))             // mod time, mod date
    fos.write(le4(0))                                // CRC-32
    fos.write(le4(entryContent.length))              // compressed size
    fos.write(le4(entryContent.length))              // uncompressed size
    fos.write(le2(fnBytes.length))                   // filename length
    fos.write(le2(0)); fos.write(le2(0))             // extra, comment lengths
    fos.write(le2(0)); fos.write(le2(0))             // disk start, internal attrs
    fos.write(le4(0))                                // external attrs
    fos.write(le4(localHeaderOffset))                // local header offset
    fos.write(fnBytes)
    val cdEntrySize = 46 + fnBytes.length
    // End of central directory record
    fos.write(Array(0x50, 0x4b, 0x05, 0x06).map(_.toByte))
    fos.write(le2(0)); fos.write(le2(0))  // disk numbers
    fos.write(le2(1)); fos.write(le2(1))  // entries on disk / total
    fos.write(le4(cdEntrySize))           // central directory size
    fos.write(le4(localEntrySize))        // central directory offset
    fos.write(le2(0))                     // comment length
    (localHeaderOffset, localEntrySize + cdEntrySize + 22)
  }

  private def writeTestJar(path: String, manifestContent: String): Unit = {
    val fos = new FileOutputStream(path)
    try {
      writeStoredZipEntry(fos, "META-INF/MANIFEST.MF", manifestContent.getBytes("UTF-8"))
    } finally fos.close()
  }

  test("detects bloop server from real macOS ps output") {
    val results = MacOsProbe.parsePsLines(psOutputLines, selfPid = -1, debug = new Debug(false), cwdResolver = noCwd)
    val bloop = results.find(_.kind == "Bloop")
    assert(bloop.isDefined, s"Expected Bloop process but got kinds: ${results.map(_.kind)}")
    assertEquals(bloop.get.pid, 1531)
    assertEquals(bloop.get.memPercent, 0.5)
    assertEquals(bloop.get.ramKb, 184288L)
  }

  test("detects mill daemon and classifies it using main class name (MillDaemonMain)") {
    val results = MacOsProbe.parsePsLines(psOutputLines, selfPid = -1, debug = new Debug(false), cwdResolver = noCwd)
    val mill = results.find(_.pid == 2983)
    assert(mill.isDefined, s"Expected mill daemon (PID 2983) to be detected but got: ${results.map(_.kind)}")
    assertEquals(mill.get.memPercent, 0.2)
    assertEquals(mill.get.ramKb, 75664L)
    assertEquals(mill.get.kind, "mill.daemon.MillDaemonMain", "mill.daemon.MillDaemonMain is not in the classification list, so it falls through to the main class name")
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
    assert(kinds.contains("mill.daemon.MillDaemonMain"), "Mill daemon should fall through to main class name")
    assert(kinds.contains("com.example.myapp.Main"), "User app should fall through to main class name")
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

  // ---------------------------------------------------------------------------
  // -jar / JAR manifest reading tests
  // ---------------------------------------------------------------------------

  test("readJarMainClass extracts Main-Class from a STORED manifest entry") {
    val jarPath = "/tmp/test-main-class.jar"
    writeTestJar(jarPath, "Manifest-Version: 1.0\r\nMain-Class: com.example.JarApp\r\n\r\n")
    assertEquals(ScalaMonitor.readJarMainClass(jarPath), Some("com.example.JarApp"))
  }

  test("readJarMainClass returns None when manifest has no Main-Class") {
    val jarPath = "/tmp/test-no-main-class.jar"
    writeTestJar(jarPath, "Manifest-Version: 1.0\r\n\r\n")
    assertEquals(ScalaMonitor.readJarMainClass(jarPath), None)
  }

  test("readJarMainClass returns None for a non-existent file") {
    assertEquals(ScalaMonitor.readJarMainClass("/tmp/does-not-exist-xyz.jar"), None)
  }

  test("extractMainClass uses manifest Main-Class for java -jar invocation") {
    val jarPath = "/tmp/test-jar-extract.jar"
    writeTestJar(jarPath, "Manifest-Version: 1.0\r\nMain-Class: com.example.ViaJar\r\n\r\n")
    val cmdline = s"/usr/bin/java -Xmx512m -jar $jarPath arg1"
    assertEquals(ScalaMonitor.extractMainClass(cmdline), Some("com.example.ViaJar"))
  }

  test("extractMainClass falls back to jar filename when manifest has no Main-Class") {
    val jarPath = "/tmp/test-jar-noclass.jar"
    writeTestJar(jarPath, "Manifest-Version: 1.0\r\n\r\n")
    val cmdline = s"/usr/bin/java -jar $jarPath"
    assertEquals(ScalaMonitor.extractMainClass(cmdline), Some("test-jar-noclass.jar"))
  }

}
