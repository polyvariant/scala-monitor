package org.polyvariant

class TuiViewTest extends munit.FunSuite with SnapshotTest {

  val sampleProcesses = List(
    ScalaProcess(100, "sbt", 2048000L, Some(0L), 55, 7.3, "~/project"),
    ScalaProcess(200, "Bloop", 157000L, None, 35, 0.5, "~/.local/bloop")
  )

  val baseState = TuiState(
    processes = sampleProcesses,
    selectedIndex = 0,
    sortColumn = SortColumn.Ram,
    sortDescending = true,
    statusMessage = None,
    statusMessageExpiresAt = 0L,
    confirmation = ConfirmationKind.None,
    confirmTargetPid = None,
    tickFrame = 0,
    showHelp = false,
    termWidth = 80
  )

  private def viewRender(state: TuiState): String =
    (new TuiApp(false)).view(state).render

  test("view renders process table with data") {
    val rendered = viewRender(baseState)
    assert(rendered.contains("100"))
    assert(rendered.contains("sbt"))
    assert(rendered.contains("2.0 GB"))
    assert(rendered.contains("7.3%"))
    assert(rendered.contains("n/a"))
    assert(rendered.contains("~/project"))
    assert(rendered.contains("Bloop"))
  }

  test("view renders status flash with success prefix") {
    val state = baseState.copy(
      statusMessage = Some("SIGTERM sent to PID 100"),
      statusMessageExpiresAt = System.currentTimeMillis() + 3000
    )
    val rendered = viewRender(state)
    assert(rendered.contains("SIGTERM sent to PID 100"))
    assert(rendered.contains("\u2713"))
  }

  test("view renders status flash with error prefix") {
    val state = baseState.copy(
      statusMessage = Some("Error: kill failed"),
      statusMessageExpiresAt = System.currentTimeMillis() + 5000
    )
    val rendered = viewRender(state)
    assert(rendered.contains("Error: kill failed"))
    assert(rendered.contains("\u2717"))
  }

  test("view renders confirmation overlay for SIGKILL") {
    val state = baseState.copy(
      confirmation = ConfirmationKind.Sigkill,
      confirmTargetPid = Some(100)
    )
    val rendered = viewRender(state)
    assert(rendered.contains("Kill"))
    assert(rendered.contains("100"))
    assert(rendered.contains("confirm"))
    assert(rendered.contains("cancel"))
    assert(rendered.contains("sbt"))
  }

  test("view renders empty state gracefully") {
    val state = baseState.copy(processes = Nil)
    val rendered = viewRender(state)
    assert(rendered.contains("No Scala processes found"))
  }

  test("view shows sort indicator in title bar") {
    val rendered = viewRender(baseState)
    assert(rendered.contains("RAM"))
  }

  test("view shows process count in title bar") {
    val rendered = viewRender(baseState)
    assert(rendered.contains("2 procs"))
  }

  test("view renders help overlay when showHelp is true") {
    val state = baseState.copy(showHelp = true)
    val rendered = viewRender(state)
    assert(rendered.contains("Navigation"))
    assert(rendered.contains("SIGTERM"))
    assert(rendered.contains("Quit"))
    assert(!rendered.contains("Bloop"))
  }

  test("table fills available terminal width") {
    val narrowRender = viewRender(baseState.copy(termWidth = 60))
    val wideRender = viewRender(baseState.copy(termWidth = 120))
    val narrowMaxLine = narrowRender.split("\n").map(_.length).max
    val wideMaxLine = wideRender.split("\n").map(_.length).max
    assert(wideMaxLine > narrowMaxLine, "wider terminal should produce wider table")
  }

  test("brand text aligns with table right border") {
    val rendered = viewRender(baseState)
    val lines = rendered.split("\n")
    val titleLine = lines(0)
    val borderLine = lines(1)
    val stripAnsi = "\u001b\\[[0-9;]*m".r
    val titleVisible = stripAnsi.replaceAllIn(titleLine, "")
    val borderVisible = stripAnsi.replaceAllIn(borderLine, "")
    assert(titleVisible.length == borderVisible.length,
      s"title visible (${titleVisible.length}) should match border visible (${borderVisible.length}), " +
      s"title line: '$titleVisible', border: '$borderVisible'")
    assert(titleVisible.endsWith("polyvariant.org"), "brand text should be at end of title row")
  }
}
