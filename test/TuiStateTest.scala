package org.polyvariant

import layoutz._

class TuiStateTest extends munit.FunSuite {

  val sampleProcesses = List(
    ScalaProcess(100, "sbt", 2048000L, Some(0L), 55, 7.3, "~/project"),
    ScalaProcess(200, "Bloop", 157000L, Some(0L), 35, 0.5, "~/.local/bloop"),
    ScalaProcess(300, "Metals", 698000L, None, 58, 2.2, "~/scala-monitor")
  )

  val initialState = TuiState(
    processes = sampleProcesses,
    selectedIndex = 0,
    sortColumn = SortColumn.Ram,
    sortDirection = SortDirection.Descending,
    statusMessage = None,
    statusMessageExpiresAt = 0L,
    confirmation = ConfirmationKind.None,
    confirmTargetPid = None,
    tickFrame = 0,
    termWidth = 80
  )

  private def updateState(msg: TuiMsg, state: TuiState = initialState): TuiState =
    (new TuiApp(Debug.noop, ProcessActionsStub)).update(msg, state)._1

  test("MoveUp at top stays at top") {
    val result = updateState(MoveUp)
    assertEquals(result.selectedIndex, 0)
  }

  test("MoveDown increments selectedIndex") {
    val result = updateState(MoveDown)
    assertEquals(result.selectedIndex, 1)
  }

  test("MoveDown at bottom stays at bottom") {
    val atBottom = initialState.copy(selectedIndex = 2)
    val result = updateState(MoveDown, atBottom)
    assertEquals(result.selectedIndex, 2)
  }

  test("SortCycle changes sort column") {
    val result = updateState(SortCycle)
    assertNotEquals(result.sortColumn, SortColumn.Ram)
  }

  test("SortCycle changes sort column and resets to descending") {
    val sorted = initialState.copy(sortColumn = SortColumn.Ram, sortDirection = SortDirection.Descending)
    val result = updateState(SortCycle, sorted)
    assertNotEquals(result.sortColumn, SortColumn.Ram)
    assertEquals(result.sortDirection, SortDirection.Descending)
  }

  test("RequestSigterm sets status flash") {
    val result = updateState(RequestSigterm)
    assert(result.statusMessage.exists(_.contains("SIGTERM")))
    assertEquals(result.confirmation, ConfirmationKind.None)
  }

  test("RequestSigkill enters confirmation mode") {
    val result = updateState(RequestSigkill)
    assertEquals(result.confirmation, ConfirmationKind.Sigkill)
    assertEquals(result.confirmTargetPid, Some(100))
  }

  test("ConfirmKill clears confirmation") {
    val confirming = initialState.copy(
      confirmation = ConfirmationKind.Sigkill,
      confirmTargetPid = Some(100)
    )
    val result = updateState(ConfirmKill, confirming)
    assertEquals(result.confirmation, ConfirmationKind.None)
    assertEquals(result.confirmTargetPid, None)
    assert(result.statusMessage.exists(_.contains("SIGKILL")))
  }

  test("CancelConfirmation clears confirmation without killing") {
    val confirming = initialState.copy(
      confirmation = ConfirmationKind.Sigkill,
      confirmTargetPid = Some(100)
    )
    val result = updateState(CancelConfirmation, confirming)
    assertEquals(result.confirmation, ConfirmationKind.None)
    assertEquals(result.statusMessage, None)
  }

  test("RequestThreadDump sets status flash") {
    val result = updateState(RequestThreadDump)
    assert(result.statusMessage.exists(_.contains("Thread dump")))
  }

  test("RequestHeapDump sets status flash") {
    val result = updateState(RequestHeapDump)
    assert(result.statusMessage.exists(_.contains("Heap dump")))
  }

  test("TickFrame increments frame counter") {
    val result = updateState(TickFrame)
    assertEquals(result.tickFrame, 1)
  }

  test("TickFrame clears expired status messages") {
    val withFlash = initialState.copy(
      statusMessage = Some("test"),
      statusMessageExpiresAt = System.currentTimeMillis() - 1000
    )
    val result = updateState(TickFrame, withFlash)
    assertEquals(result.statusMessage, None)
  }

  test("TickFrame preserves non-expired status messages") {
    val withFlash = initialState.copy(
      statusMessage = Some("test"),
      statusMessageExpiresAt = System.currentTimeMillis() + 5000
    )
    val result = updateState(TickFrame, withFlash)
    assertEquals(result.statusMessage, Some("test"))
  }

  test("ProcessesLoaded clamps selectedIndex if list shrinks") {
    val result = updateState(
      ProcessesLoaded(List(sampleProcesses.head)),
      initialState.copy(selectedIndex = 2)
    )
    assertEquals(result.selectedIndex, 0)
  }

  test("Quit returns exit command") {
    val (_, cmd) = (new TuiApp(Debug.noop, ProcessActionsStub)).update(Quit, initialState)
    assertEquals(cmd, Cmd.exit)
  }

  test("ToggleHelp toggles showHelp on") {
    val result = updateState(ToggleHelp)
    assertEquals(result.showHelp, true)
  }

  test("ToggleHelp toggles showHelp off") {
    val withHelp = initialState.copy(showHelp = true)
    val result = updateState(ToggleHelp, withHelp)
    assertEquals(result.showHelp, false)
  }

  test("JumpToFirst sets selectedIndex to 0") {
    val atBottom = initialState.copy(selectedIndex = 2)
    val result = updateState(JumpToFirst, atBottom)
    assertEquals(result.selectedIndex, 0)
  }

  test("JumpToFirst at top stays at top") {
    val result = updateState(JumpToFirst)
    assertEquals(result.selectedIndex, 0)
  }

  test("JumpToLast sets selectedIndex to last process") {
    val result = updateState(JumpToLast)
    assertEquals(result.selectedIndex, 2)
  }

  test("JumpToLast at bottom stays at bottom") {
    val atBottom = initialState.copy(selectedIndex = 2)
    val result = updateState(JumpToLast, atBottom)
    assertEquals(result.selectedIndex, 2)
  }
}
