package org.polyvariant

class TerminalSizeTest extends munit.FunSuite {

  test("parseOutput: valid input returns correct dimensions") {
    assertEquals(SttyTerminalSize.parseOutput("50 200"), (200, 50))
  }

  test("parseOutput: width capped at 210") {
    assertEquals(SttyTerminalSize.parseOutput("50 300"), (210, 50))
  }

  test("parseOutput: width floored at 80") {
    assertEquals(SttyTerminalSize.parseOutput("50 40"), (80, 50))
  }

  test("parseOutput: garbage input returns default") {
    assertEquals(SttyTerminalSize.parseOutput("garbage"), (80, 24))
  }

  test("parseOutput: empty input returns default") {
    assertEquals(SttyTerminalSize.parseOutput(""), (80, 24))
  }

  test("parseOutput: zero values return default") {
    assertEquals(SttyTerminalSize.parseOutput("0 0"), (80, 24))
  }

  test("FixedTerminalSize.query returns configured dimensions") {
    val ts = FixedTerminalSize(200, 50)
    assertEquals(ts.query(), (200, 50))
  }

  test("FixedTerminalSize.query returns default-like dimensions") {
    val ts = FixedTerminalSize(80, 24)
    assertEquals(ts.query(), (80, 24))
  }

  test("parseOutput: height floored at 1") {
    assertEquals(SttyTerminalSize.parseOutput("1 100"), (100, 1))
  }

  test("parseOutput: negative values return default") {
    assertEquals(SttyTerminalSize.parseOutput("-5 -10"), (80, 24))
  }

  test("parseOutput: single number returns default") {
    assertEquals(SttyTerminalSize.parseOutput("50"), (80, 24))
  }

  test("parseOutput: width exactly at boundaries") {
    assertEquals(SttyTerminalSize.parseOutput("30 210"), (210, 30))
    assertEquals(SttyTerminalSize.parseOutput("30 80"), (80, 30))
  }

  test("TerminalSize factory creates SttyTerminalSize") {
    val ts = TerminalSize(Debug.noop)
    assert(ts.isInstanceOf[SttyTerminalSize])
  }
}
