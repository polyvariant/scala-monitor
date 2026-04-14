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
    tickFrame = 0
  )

  private def viewRender(state: TuiState): String =
    (new TuiApp(false)).view(state).render

  test("view renders process table with selected row marker") {
    val rendered = viewRender(baseState)
    assert(rendered.contains("100"))
    assert(rendered.contains("sbt"))
    assert(rendered.contains("2.0 GB"))
    assert(rendered.contains("7.3%"))
    assert(rendered.contains("n/a"))
    assert(rendered.contains("~/project"))
  }

  test("view renders status flash message") {
    val state = baseState.copy(
      statusMessage = Some("SIGTERM sent to PID 100"),
      statusMessageExpiresAt = System.currentTimeMillis() + 3000
    )
    val rendered = viewRender(state)
    assert(rendered.contains("SIGTERM sent to PID 100"))
  }

  test("view renders confirmation overlay for SIGKILL") {
    val state = baseState.copy(
      confirmation = ConfirmationKind.Sigkill,
      confirmTargetPid = Some(100)
    )
    val rendered = viewRender(state)
    assert(rendered.contains("Force kill"))
    assert(rendered.contains("100"))
    assert(rendered.contains("confirm"))
    assert(rendered.contains("cancel"))
  }

  test("view renders empty state gracefully") {
    val state = baseState.copy(processes = Nil)
    val rendered = viewRender(state)
    assert(rendered.length > 0)
  }

  test("view shows sort indicator") {
    val rendered = viewRender(baseState)
    assert(rendered.contains("RAM"))
  }
}
