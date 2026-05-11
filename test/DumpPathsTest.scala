package org.polyvariant

class DumpPathsTest extends munit.FunSuite {

  test("dumpFileName includes kind, pid, label, timestamp, extension") {
    val result = DumpPaths.dumpFileName("thread", 12345, "sbt", "txt")
    assert(result.startsWith("thread-dump-12345-sbt-"), s"Expected prefix 'thread-dump-12345-sbt-', got: $result")
    assert(result.endsWith(".txt"), s"Expected to end with '.txt', got: $result")
    val timestampPart = result.stripPrefix("thread-dump-12345-sbt-").stripSuffix(".txt")
    assert(timestampPart.nonEmpty, "Timestamp part should not be empty")
    assert(timestampPart.contains("T"), s"Timestamp should contain 'T', got: $timestampPart")
  }

  test("dumpFileName omits kind when empty — no double dash") {
    val result = DumpPaths.dumpFileName("heap", 99999, "", "hprof")
    assert(!result.contains("--"), s"Should not contain double dash, got: $result")
    assert(result.startsWith("heap-dump-99999-"), s"Expected prefix 'heap-dump-99999-', got: $result")
    assert(result.endsWith(".hprof"), s"Expected to end with '.hprof', got: $result")
  }

  test("dumpFileName includes kind when provided") {
    val result = DumpPaths.dumpFileName("thread", 42, "metals", "txt")
    assert(result.contains("-metals-"), s"Expected '-metals-' in filename, got: $result")
  }

  test("dumpFileName uses hyphens instead of colons in timestamp") {
    val result = DumpPaths.dumpFileName("thread", 1, "test", "txt")
    assert(!result.contains(":"), s"Filename should not contain colons (filesystem-unsafe), got: $result")
  }

  test("dumpFileName with empty kind and empty label produces clean name") {
    val result = DumpPaths.dumpFileName("heap", 100, "", "hprof")
    assert(!result.contains("--"), s"No double dash expected, got: $result")
    assert(result.startsWith("heap-dump-100-"), s"Expected prefix 'heap-dump-100-', got: $result")
  }

  test("resolveDumpsDir returns custom path when provided") {
    assertEquals(DumpPaths.resolveDumpsDir(Some("/custom")), "/custom")
  }

  test("resolveDumpsDir returns custom path with trailing slash") {
    assertEquals(DumpPaths.resolveDumpsDir(Some("/tmp/dumps/")), "/tmp/dumps/")
  }

  test("resolveDumpsDir uses XDG_CACHE_HOME when set") {
    val result = DumpPaths.resolveDumpsDirWithEnv(None, Some("/xdg-cache"))
    assertEquals(result, "/xdg-cache/scala-monitor/dumps")
  }

  test("resolveDumpsDir falls back to ~/.cache when XDG_CACHE_HOME is empty") {
    val home = System.getProperty("user.home")
    val result = DumpPaths.resolveDumpsDirWithEnv(None, None)
    assertEquals(result, s"$home/.cache/scala-monitor/dumps")
  }

  test("resolveDumpsDir ignores XDG_CACHE_HOME when override is provided") {
    assertEquals(DumpPaths.resolveDumpsDirWithEnv(Some("/override"), Some("/xdg-cache")), "/override")
  }

  test("resolveDumpsDir ignores empty XDG_CACHE_HOME") {
    val home = System.getProperty("user.home")
    val result = DumpPaths.resolveDumpsDirWithEnv(None, Some(""))
    assertEquals(result, s"$home/.cache/scala-monitor/dumps")
  }

}
