package org.polyvariant

class TerminalSizeTest extends munit.FunSuite {

  // Pure function mirroring SttyTerminalSize parsing logic
  private def parseSttyOutput(output: String): (Int, Int) = {
    val DefaultSize = (80, 24)
    val MaxWidth = 210
    val MinWidth = 80
    val MinHeight = 1
    val parts = output.trim.split("\\s+")
    if (parts.length == 2) {
      val rows = parts(0).toInt
      val cols = parts(1).toInt
      if (rows > 0 && cols > 0) {
        val width = math.max(MinWidth, math.min(MaxWidth, cols))
        val height = math.max(MinHeight, rows)
        (width, height)
      } else {
        DefaultSize
      }
    } else {
      DefaultSize
    }
  }

  test("parseSttyOutput: valid input returns correct dimensions") {
    assertEquals(parseSttyOutput("50 200"), (200, 50))
  }

  test("parseSttyOutput: width capped at 210") {
    assertEquals(parseSttyOutput("50 300"), (210, 50))
  }

  test("parseSttyOutput: width floored at 80") {
    assertEquals(parseSttyOutput("50 40"), (80, 50))
  }

  test("parseSttyOutput: garbage input returns default") {
    assertEquals(parseSttyOutput("garbage"), (80, 24))
  }

  test("parseSttyOutput: empty input returns default") {
    assertEquals(parseSttyOutput(""), (80, 24))
  }

  test("parseSttyOutput: zero values return default") {
    assertEquals(parseSttyOutput("0 0"), (80, 24))
  }

  test("FixedTerminalSize.query returns configured dimensions") {
    val ts = FixedTerminalSize(200, 50)
    assertEquals(ts.query(), (200, 50))
  }

  test("FixedTerminalSize.query returns default-like dimensions") {
    val ts = FixedTerminalSize(80, 24)
    assertEquals(ts.query(), (80, 24))
  }

  test("parseSttyOutput: height floored at 1") {
    assertEquals(parseSttyOutput("1 100"), (100, 1))
  }

  test("parseSttyOutput: negative values return default") {
    assertEquals(parseSttyOutput("-5 -10"), (80, 24))
  }

  test("parseSttyOutput: single number returns default") {
    assertEquals(parseSttyOutput("50"), (80, 24))
  }

  test("parseSttyOutput: width exactly at boundaries") {
    assertEquals(parseSttyOutput("30 210"), (210, 30))
    assertEquals(parseSttyOutput("30 80"), (80, 30))
  }
}
