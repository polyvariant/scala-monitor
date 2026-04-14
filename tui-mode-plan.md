# TUI Mode Implementation Plan

## Overview

Add an interactive TUI mode to `scala-monitor` using the [layoutz](https://github.com/mattlianje/layoutz) library. Users run `scala-monitor --watch` to launch a live, navigable process monitor with kill, thread dump, and heap dump actions.

**The TUI is an additional mode alongside the existing CLI -- not a replacement.** The current one-shot behavior (`scala-monitor`, `scala-monitor -f type=sbt`, `scala-monitor -o pid`) remains unchanged and untouched. The TUI is accessed only via the new `--watch` flag. Both modes share the same `discover()` pipeline and `ScalaProcess` data model.

```
scala-monitor                   # one-shot table output (UNCHANGED)
scala-monitor -f type=sbt       # filtered one-shot (UNCHANGED)
scala-monitor -o pid            # PID-only output (UNCHANGED)
scala-monitor --watch           # NEW: interactive TUI mode
```

## Locked Decisions

| Decision | Choice |
|----------|--------|
| Library | `layoutz` 0.7.0 (zero-dep, Scala Native, JVM, JS) |
| SIGTERM | `d` -- immediate + status flash |
| SIGKILL | `Ctrl+K` -- confirmation overlay first |
| Thread dump | `t` -- mocked, return message only |
| Heap dump | `h` -- mocked, return message only |
| Navigation | Arrow keys + `j`/`k` (vim-style) |
| Sort | `F` -- cycle PID, TYPE, RAM, MEM%, PROJECT |
| Quit | `q` or `Ctrl+C` -- both clean exit |
| Alternate screen | Yes (layoutz `clearOnStart`/`clearOnExit`) |
| Refresh interval | 1 second |
| macOS threads | Fix via `proc_pidinfo` PROC_PIDTASKINFO |
| macOS swap | Show `n/a` (platform limitation, no API exposes per-process swap) |
| Kill UX | Hybrid -- `d` immediate SIGTERM + flash, `Ctrl+K` SIGKILL with confirmation prompt |

## Current Codebase State (post-rebase)

The codebase has evolved since the initial plan. Key changes already on main:

- **Tests exist**: `test/PsOutputParsingSuite.scala` with 10 tests + `test/ps-output.txt` fixture
- **Munit already configured**: `project.scala` has `//> using test.dep org.scalameta::munit_native0.5:1.3.0`
- **MacOsProbe refactored**: `parsePsLines` extracted to companion object with injectable `cwdResolver` param (testable)
- **VSZ column dropped**: No longer in `ScalaProcess` or `renderTable`
- **macOS comma parsing fixed**: `.replace(',', '.')` for locale-aware decimal parsing
- **Integration test expanded**: More assertions, `--help` check
- **`discover()` is `private`**: TuiApp needs access (plan calls for making it public)
- **`formatMemory` is `private`**: TuiApp needs access for table rendering

## Architecture

```
project.scala (+layoutz dep only -- munit already present)
       |
       +-- ScalaMonitor.scala (MODIFY -- add --watch flag, make discover/formatMemory accessible)
       |       |
       |       +-- PlatformProbe.scala / LinuxProbe.scala (UNCHANGED)
       |       +-- MacOsProbe.scala (MODIFY -- add readThreadCount, use in discover)
       |       +-- MacOsExtern.scala (MODIFY -- add PROC_PIDTASKINFO constants)
       |
       +-- TuiApp.scala (NEW -- LayoutzApp[State, Msg])
       |       |
       |       +-- ProcessActions.scala (NEW -- kill, thread dump, heap dump)
       |
       +-- scripts/integration-test.sh (MODIFY -- add --watch assertions)
       +-- test/ (MODIFY -- add TUI test suites alongside existing PsOutputParsingSuite)
```

## Screen Layout

```
+--------------------------------------------------------------------------+
| SCALA PROCESS MONITOR -- 3 processes -- 3.1 GB RAM                       |
| Refresh: 1s  |  Sort: RAM v                                            |
+--------------------------------------------------------------------------+
|  PID      TYPE        RSS        SWAP    MEM%  THR  PROJECT             |
+--------------------------------------------------------------------------+
|> 2513087  sbt       2.3 GB       0 kB    7.3%   55  ~/Code/smithy      |
|  2503995  Metals    698 MB       n/a      2.2%   58  ~/Code/scala-monitor|
|  2647652  Bloop     157 MB       0 kB    0.5%   35  ~/.local/bloop      |
+--------------------------------------------------------------------------+
| Arrows navigate | d SIGTERM | Ctrl+K KILL | t threads | h heap | F sort |
+--------------------------------------------------------------------------+
 SIGTERM sent to PID 2513087                                            <- flash
```

Confirmation overlay (after Ctrl+K):

```
+--------------------------------------------------------------------------+
|  !! Force kill PID 2513087 (sbt)?  Enter=confirm  Esc=cancel            |
+--------------------------------------------------------------------------+
```

## Keybindings

| Key | Action |
|-----|--------|
| `Up` / `j` | Move selection up |
| `Down` / `k` | Move selection down |
| `d` | Send SIGTERM to selected (immediate, status flash) |
| `Ctrl+K` | Send SIGKILL to selected (confirmation overlay first) |
| `t` | Thread dump selected (mocked) |
| `h` | Heap dump selected (mocked) |
| `F` | Cycle sort column (PID -> TYPE -> RAM -> MEM% -> PROJECT) |
| `Enter` | Confirm kill (on confirmation overlay) |
| `Esc` | Cancel (on confirmation overlay) |
| `q` | Quit |
| `Ctrl+C` | Quit |

## State Model

```scala
enum SortColumn { case Pid, Kind, Ram, MemPercent, Project }

enum ConfirmationKind { case Sigkill, None }

case class TuiState(
  processes: List[ScalaProcess],       // current snapshot from discover()
  selectedIndex: Int,                  // highlighted row (0-based)
  sortColumn: SortColumn,              // active sort column
  sortDescending: Boolean,             // sort direction
  statusMessage: Option[String],       // flash message shown below footer
  statusMessageExpiresAt: Long,        // epoch millis when to clear flash
  confirmation: ConfirmationKind,      // active confirmation prompt
  confirmTargetPid: Option[Int],       // PID being confirmed for kill
  tickFrame: Int                       // animation frame counter
)
```

## Message Type (Elm update loop)

```scala
sealed trait TuiMsg

case object RefreshProcesses extends TuiMsg           // periodic (every 1s)
case class ProcessesLoaded(procs: List[ScalaProcess]) extends TuiMsg
case object MoveUp extends TuiMsg
case object MoveDown extends TuiMsg
case object SortCycle extends TuiMsg                  // F key
case object RequestSigterm extends TuiMsg             // d key
case object RequestSigkill extends TuiMsg             // Ctrl+K key
case object ConfirmKill extends TuiMsg                // Enter on overlay
case object CancelConfirmation extends TuiMsg         // Esc on overlay
case object RequestThreadDump extends TuiMsg          // t key
case object RequestHeapDump extends TuiMsg            // h key
case class ActionCompleted(msg: String) extends TuiMsg
case class ActionFailed(err: String) extends TuiMsg
case object ClearStatus extends TuiMsg
case object TickFrame extends TuiMsg                  // animation tick (100ms)
case object Quit extends TuiMsg                       // q key
```

## Commit Strategy

### C1: `feat: add layoutz dependency`

**Files**: `project.scala` (MODIFY)

Add layoutz as a dependency. Munit is already configured (`munit_native0.5:1.3.0`).

```scala
//> using dep xyz.matthieucourt::layoutz_native0.5:0.7.0
```

**QA**: `scala-cli --power package . -o scala-monitor -f` compiles successfully.

**Effort**: 5 min

---

### C2: `feat: fix macOS thread count via proc_pidinfo`

**Files**: `MacOsExtern.scala` (MODIFY), `MacOsProbe.scala` (MODIFY)

**MacOsExtern.scala** -- add constants:

```scala
// Add to libproc @extern object:
private val ProcPidTaskInfo = 4  // flavor for proc_taskinfo struct
private val ProcTaskInfoSize = 80  // struct size in bytes
```

`proc_pidinfo` already exists as an `@extern` in `libproc`. Only the flavor constant and struct size are new.

**MacOsProbe.scala** -- add thread count reader. The `discover()` method now delegates to `MacOsProbe.parsePsLines()`. Add `readThreadCount()` private method and use it when constructing `ScalaProcess` (replacing `threads = 0`). The `parsePsLines` method in the companion object takes `cwdResolver: Int => Option[String]` -- add a similar `threadResolver: Int => Int` parameter, or keep `readThreadCount` as a class method called alongside `cwdResolver` in the `discover()` wrapper.

Simplest approach: keep `readThreadCount` as a private method on the class (it needs `Zone` and FFI, same as `readProcessWorkingDirectory`), call it in `discover()` before passing to `parsePsLines`, then thread the thread count through. Since `parsePsLines` constructs `ScalaProcess` with `threads = 0`, modify it to accept a `threadCountResolver: Int => Int` parameter (defaulting to `_ => 0` for test compatibility).

```scala
private def readThreadCount(pid: Int): Int = Zone.acquire { implicit z =>
  val buffer = alloc[Byte](ProcTaskInfoSize)
  val bytesWritten = libproc.proc_pidinfo(pid, ProcPidTaskInfo, 0L, buffer, ProcTaskInfoSize)
  if (bytesWritten <= 0) 0
  else {
    val ptr = (buffer + 64).asInstanceOf[Ptr[CUnsignedInt]]
    !ptr.toInt
  }
}
```

**QA**: Build on macOS, run, verify THR column shows real values (not 0). Existing `PsOutputParsingSuite` should still pass (it uses `noCwd` resolver, thread resolver defaults to `_ => 0`).

**Effort**: 25 min

---

### C3: `feat: add ProcessActions (kill + mocked dumps)`

**Files**: `ProcessActions.scala` (NEW)

```scala
package org.polyvariant

import scala.scalanative.posix.signal
import scala.scalanative.posix.unistd

object ProcessActions {

  def sendSigterm(pid: Int): Either[String, String] = {
    val result = unistd.kill(pid, signal.SIGTERM)
    if (result == 0) Right(s"SIGTERM sent to PID $pid")
    else Left(s"kill($pid, SIGTERM) failed")
  }

  def sendSigkill(pid: Int): Either[String, String] = {
    val result = unistd.kill(pid, signal.SIGKILL)
    if (result == 0) Right(s"SIGKILL sent to PID $pid")
    else Left(s"kill($pid, SIGKILL) failed")
  }

  def threadDump(pid: Int): Either[String, String] = {
    // MOCKED: real implementation would shell out to:
    // jcmd <pid> Thread.print -l > /tmp/threads-<pid>-<timestamp>.txt
    Right(s"Thread dump requested for PID $pid -> /tmp/threads-$pid.hprof")
  }

  def heapDump(pid: Int): Either[String, String] = {
    // MOCKED: real implementation would shell out to:
    // jcmd <pid> GC.heap_dump /tmp/heap-<pid>-<timestamp>.hprof
    Right(s"Heap dump requested for PID $pid -> /tmp/heap-$pid.hprof")
  }
}
```

**QA**: Compiles. Integration: manually test `sendSigterm` against a real process.

**Effort**: 25 min

---

### C4: `feat: add TUI mode (--watch flag)`

**Files**: `TuiApp.scala` (NEW), `ScalaMonitor.scala` (MODIFY)

#### TuiApp.scala (~250 LOC)

Extends `LayoutzApp[TuiState, TuiMsg]` from layoutz.

**init**: Call `ScalaMonitor.discover(debug)`, return initial state with processes sorted by RAM descending.

**update** -- key message handlers:

| Message | Behavior |
|---------|----------|
| `RefreshProcesses` | Fire `Cmd.task { discover() }` off-thread, result as `ProcessesLoaded` |
| `ProcessesLoaded(procs)` | Sort and replace `state.processes`. Reset `selectedIndex` if out of bounds |
| `MoveUp` | `selectedIndex = max(0, current - 1)` |
| `MoveDown` | `selectedIndex = min(size - 1, current + 1)` |
| `SortCycle` | Cycle sort column. Toggle direction if same column pressed again |
| `RequestSigterm` | Call `ProcessActions.sendSigterm(pid)` synchronously, set status flash |
| `RequestSigkill` | Set `confirmation = Sigkill`, `confirmTargetPid = Some(pid)` |
| `ConfirmKill` | Call `ProcessActions.sendSigkill(pid)`, clear confirmation, set status flash |
| `CancelConfirmation` | Set `confirmation = None`, `confirmTargetPid = None` |
| `RequestThreadDump` | Call `ProcessActions.threadDump(pid)`, set status flash |
| `RequestHeapDump` | Call `ProcessActions.heapDump(pid)`, set status flash |
| `ActionCompleted(msg)` | Set `statusMessage = Some(msg)`, expiry in 3 seconds |
| `ActionFailed(err)` | Set `statusMessage = Some("Error: " + err)`, expiry in 5 seconds |
| `TickFrame` | Increment frame counter. Clear expired status messages |
| `Quit` | Return `(state, Cmd.exit)` |

**subscriptions**:

```scala
Sub.batch(
  Sub.time.everyMs(1000, RefreshProcesses),
  Sub.time.everyMs(100, TickFrame),
  Sub.onKeyPress {
    case Key.Char('q')                     => Some(Quit)
    case Key.Up | Key.Char('j')             => Some(MoveUp)
    case Key.Down | Key.Char('k')           => Some(MoveDown)
    case Key.Char('d')                      => Some(RequestSigterm)
    case Key.Ctrl('K')                      => Some(RequestSigkill)
    case Key.Char('t')                      => Some(RequestThreadDump)
    case Key.Char('h')                      => Some(RequestHeapDump)
    case Key.Char('F')                      => Some(SortCycle)
    case Key.Enter if confirming            => Some(ConfirmKill)
    case Key.Escape if confirming           => Some(CancelConfirmation)
    case _                                  => None
  }
)
```

**view** -- layoutz elements:

- **Header**: `banner("SCALA PROCESS MONITOR -- ...").border(Border.Round)`
- **Info bar**: `row(statusCard("Refresh", "1s"), statusCard("Sort", "RAM v"))`
- **Table**: `table(headers, rows).border(Border.Round)` -- selected row prefixed with `>`, optionally `.bg(Color.DarkGray)`
- **Footer**: `row(...).color(Color.BrightBlack)` with key hints
- **Status flash**: Conditional `"! " + msg` in yellow below footer
- **Confirmation overlay**: `box("!! Confirm")(...).border(Border.Round).color(Color.Red)` replacing footer when active

**run()** entry point:

```scala
def run(debug: Boolean): Unit = {
  debugEnabled = debug
  TuiAppImpl.run(
    tickIntervalMs = 100,
    renderIntervalMs = 50,
    clearOnStart = true,
    clearOnExit = true,
    showQuitMessage = false,
    quitKey = Key.Ctrl('C')
  )
}
```

#### ScalaMonitor.scala changes (minimal -- existing behavior untouched):

The `run()` method gains a single `--watch` flag. When set, it delegates to `TuiApp.run()` and returns immediately. When unset, the existing one-shot code path runs identically to before.

```scala
@main
def run(
  @arg(short = 'o', ...) output: String = "full",
  @arg(short = 'f', ...) filter: Seq[String] = Seq.empty,
  @arg(short = 'd', ...) debug: Flag = Flag(),
  @arg(short = 'w', doc = "Interactive TUI mode (like top)") watch: Flag = Flag()  // NEW
): Unit = {
  if (watch.value) {
    TuiApp.run(debug.value)  // NEW: launches layoutz TUI, blocks until quit
  } else {
    // EXISTING CODE -- completely unchanged
    val processes = discover(debug.value)
    val (filtered, warnings) = applyFilters(processes, filter.toList)
    // ...
  }
}
```

Additional changes:
- Make `discover()` accessible: change `private def discover` to `def discover` (TuiApp calls it for periodic refresh)
- Make `formatMemory()` accessible: change `private def formatMemory` to `def formatMemory` (TuiApp uses it in view)
- Everything else in `ScalaMonitor.scala` is untouched: `renderTable`, `classify`, `isScalaProcess`, `applyFilters`, `matchesGlob`, etc.

**QA**:
1. `scala-monitor` (no flags) still works as one-shot -- **UNCHANGED behavior, verify no regression**
2. `scala-monitor -f type=sbt -o pid` still works -- **UNCHANGED, verify no regression**
3. `scala-monitor --watch` launches TUI, shows process table
4. Arrow keys / j/k navigate
5. `d` sends SIGTERM, flash appears
6. `Ctrl+K` shows confirmation, Enter confirms, Esc cancels
7. `t` and `h` show mocked status messages
8. `F` cycles sort column
9. `q` and `Ctrl+C` exit cleanly, terminal restored
10. Process list refreshes every ~1 second

**Effort**: 60 min

---

### C5: `test: integration smoke test for TUI mode`

**Files**: `scripts/integration-test.sh` (MODIFY)

- Add assertion that `./scala-monitor --help` includes `--watch` flag
- Add assertion that one-shot mode (`./scala-monitor`) still works
- Verify `./scala-monitor -o pid` returns PIDs only

**QA**: `./scripts/integration-test.sh` passes on both Linux and macOS.

**Effort**: 15 min

---

## Dependency Graph

```
C1 (layoutz dep) --------+
                         +--> C4 (TuiApp + ScalaMonitor) --> C5 (integration test)
C3 (ProcessActions) -----+
C2 (macOS threads) ----------------------> (independent, can run any time)
```

**Parallel opportunities**: C2 and C3 are fully independent. C4 depends on C1 + C3.

## Effort Estimate

| Commit | Description | Effort |
|--------|-------------|--------|
| C1 | Add layoutz dependency | 5 min |
| C2 | macOS thread count fix | 20 min |
| C3 | ProcessActions (kill + mocked dumps) | 25 min |
| C4 | TUI mode (--watch flag) | 60 min |
| C5 | Integration smoke test | 15 min |
| **Total** | | **~2 hours** |

## Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| layoutz SN 0.5.10 incompatibility | Medium | High | Test compile in C1 immediately. layoutz is cross-built for Native 0.5. Fallback: build from source |
| macOS proc_taskinfo struct offset wrong | Low | Medium | Well-documented struct. Test against known process. Can verify with `lldb` |
| Blocking refresh freezes TUI | Low | Medium | `Cmd.task` runs on daemon thread. 1s interval is generous. Worst case: brief stutter |
| Terminal too narrow for table | Medium | Low | Columns are fixed-width. Truncate PROJECT path. Defer responsive layout |
| Ctrl+C handling | Low | Low | layoutz `quitKey` intercepts at raw input level before OS signal. Clean exit via `clearOnExit` |
| `posix.signal.kill` import path | Low | Low | Available in `scala.scalanative.posix.signal`. Verify early in C3 |
| Sort state lost on refresh | Low | Low | Sort is in TuiState (not reset on refresh). Only `selectedIndex` resets if list shrinks |

## Test Plan

### Approach: munit + inline snapshot assertions

**Framework**: munit 1.3.0 (latest, confirmed Scala Native compatible, published Apr 2026)

**Snapshot strategy**: Inline snapshot assertions using munit's `assertEquals` with multi-line string literals. No external snapshot library needed.

**Why not snapshot4s?** snapshot4s (by SiriusXM, 66 stars) is the best Scala snapshot library -- supports munit, ScalaTest, Weaver, Scala Native, and Scala.js. It offers both inline (`assertInlineSnapshot(value, ???)`) and file-based (`assertFileSnapshot(value, "name")`) snapshots with an `sbt-snapshot4sPromote` command to rewrite source files. However, its promotion mechanism requires the `sbt-snapshot4s` sbt plugin, and this project uses scala-cli with no sbt. The runtime assertions would work without sbt, but the promotion workflow (the key value of snapshot4s) would be broken. A custom 10-LOC `assertSnapshot` helper using `assertEquals` achieves the same thing for our use case.

**Why not snapshot4s file-based only?** Even without promotion, file snapshots are viable: write `.snap` files by hand, compare in tests. But for TUI rendering tests, inline snapshots are superior because:
- The expected output lives right next to the test -- you see the rendered TUI while reading the test
- No external files to manage
- Easy to update: just copy-paste the actual output into the string literal
- The TUI render output is ~20 lines, not hundreds -- inline is readable

### What is testable

| Component | Testable without TTY? | Strategy |
|-----------|----------------------|----------|
| `TuiState.update()` (state transitions) | Yes -- pure function | Unit tests: feed message + state, assert new state |
| `TuiApp.view()` -- rendered output | Yes -- returns `Element.render` String | Inline snapshot: compare rendered ANSI string |
| `ProcessActions` (mocked) | Yes -- returns `Either` | Unit tests: assert return values |
| `ProcessActions` (real kill) | Yes, but needs real PID | Manual test only (dangerous in CI) |
| Sort logic | Yes -- pure function | Unit tests on `List[ScalaProcess]` |
| Key routing in subscriptions | Yes -- pattern match | Unit tests: feed `Key.*`, assert `Option[TuiMsg]` |
| `LayoutzApp.run()` event loop | **No** -- needs raw stdin + TTY | Manual QA only |
| Actual terminal rendering | **No** -- needs TTY | Manual QA only |
| Resize handling (SIGWINCH) | **No** -- needs terminal emulator | Manual QA only |

### Test infrastructure

#### project.scala (already configured)

Munit is already present on main:

```scala
//> using test.dep org.scalameta::munit_native0.5:1.3.0
```

Only layoutz needs to be added (C1).

#### Custom snapshot helper (10 LOC)

In `test/SnapshotTest.scala`:

```scala
package org.polyvariant

trait SnapshotTest {
  self: munit.FunSuite =>

  /** Assert two strings are equal, with a hint to copy-paste the actual as the new expected. */
  def assertSnapshot(obtained: String, expected: String)(implicit loc: munit.Location): Unit =
    assertEquals(obtained.stripMargin.trim, expected.stripMargin.trim)

  /** Compare against a file in src/test/resources/snapshots/ */
  def assertFileSnapshot(obtained: String, name: String)(implicit loc: munit.Location): Unit = {
    val resourcePath = s"snapshots/$name.snap"
    val expected = scala.io.Source.fromResource(resourcePath).mkString
    assertEquals(obtained.stripMargin.trim, expected.stripMargin.trim)
  }
}
```

#### Running tests

```bash
# Run all tests (JVM mode -- fast, no native compilation)
scala-cli test .

# Run specific test
scala-cli test . --test-only "org.polyvariant.TuiStateTest"
```

Tests run in JVM mode by default (fast, no native compilation needed). The TUI logic (state, rendering, actions) is pure Scala with no native FFI dependencies -- it doesn't need Scala Native to test. Only the final integration smoke test requires a native binary.

### Test files

#### `TuiStateTest.scala` -- State transition tests

Tests the pure `update(msg, state) -> state` function for every message type.

```scala
object TuiStateTest extends munit.FunSuite {

  val sampleProcesses = List(
    ScalaProcess(100, "sbt", 2048000L, Some(0L), 55, 7.3, "~/project"),
    ScalaProcess(200, "Bloop", 157000L, Some(0L), 35, 0.5, "~/.local/bloop"),
    ScalaProcess(300, "Metals", 698000L, None, 58, 2.2, "~/scala-monitor")
  )

  val initialState = TuiState(
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

  test("MoveUp at top stays at top") {
    val result = TuiApp.update(MoveUp, initialState)
    assertEquals(result.selectedIndex, 0)
  }

  test("MoveDown increments selectedIndex") {
    val result = TuiApp.update(MoveDown, initialState)
    assertEquals(result.selectedIndex, 1)
  }

  test("MoveDown at bottom stays at bottom") {
    val atBottom = initialState.copy(selectedIndex = 2)
    val result = TuiApp.update(MoveDown, atBottom)
    assertEquals(result.selectedIndex, 2)
  }

  test("SortCycle changes sort column") {
    val result = TuiApp.update(SortCycle, initialState)
    assertNotEquals(result.sortColumn, SortColumn.Ram)
  }

  test("SortCycle toggles direction on same column") {
    val sorted = initialState.copy(sortColumn = SortColumn.Ram, sortDescending = true)
    val result = TuiApp.update(SortCycle, sorted)
    assertEquals(result.sortDescending, false)
  }

  test("RequestSigterm sets status flash") {
    // Note: real kill is mocked in test by overriding ProcessActions
    val result = TuiApp.update(RequestSigterm, initialState)
    assert(result.statusMessage.exists(_.contains("SIGTERM")))
    assert(result.confirmation == ConfirmationKind.None)
  }

  test("RequestSigkill enters confirmation mode") {
    val result = TuiApp.update(RequestSigkill, initialState)
    assertEquals(result.confirmation, ConfirmationKind.Sigkill)
    assertEquals(result.confirmTargetPid, Some(100))
  }

  test("ConfirmKill clears confirmation") {
    val confirming = initialState.copy(
      confirmation = ConfirmationKind.Sigkill,
      confirmTargetPid = Some(100)
    )
    val result = TuiApp.update(ConfirmKill, confirming)
    assertEquals(result.confirmation, ConfirmationKind.None)
    assertEquals(result.confirmTargetPid, None)
    assert(result.statusMessage.exists(_.contains("SIGKILL")))
  }

  test("CancelConfirmation clears confirmation without killing") {
    val confirming = initialState.copy(
      confirmation = ConfirmationKind.Sigkill,
      confirmTargetPid = Some(100)
    )
    val result = TuiApp.update(CancelConfirmation, confirming)
    assertEquals(result.confirmation, ConfirmationKind.None)
    assertEquals(result.statusMessage, None)
  }

  test("RequestThreadDump sets status flash") {
    val result = TuiApp.update(RequestThreadDump, initialState)
    assert(result.statusMessage.exists(_.contains("Thread dump")))
  }

  test("RequestHeapDump sets status flash") {
    val result = TuiApp.update(RequestHeapDump, initialState)
    assert(result.statusMessage.exists(_.contains("Heap dump")))
  }

  test("TickFrame increments frame counter") {
    val result = TuiApp.update(TickFrame, initialState)
    assertEquals(result.tickFrame, 1)
  }

  test("TickFrame clears expired status messages") {
    val withFlash = initialState.copy(
      statusMessage = Some("test"),
      statusMessageExpiresAt = System.currentTimeMillis() - 1000
    )
    val result = TuiApp.update(TickFrame, withFlash)
    assertEquals(result.statusMessage, None)
  }

  test("TickFrame preserves non-expired status messages") {
    val withFlash = initialState.copy(
      statusMessage = Some("test"),
      statusMessageExpiresAt = System.currentTimeMillis() + 5000
    )
    val result = TuiApp.update(TickFrame, withFlash)
    assertEquals(result.statusMessage, Some("test"))
  }

  test("ProcessesLoaded clamps selectedIndex if list shrinks") {
    val result = TuiApp.update(
      ProcessesLoaded(List(sampleProcesses.head)),
      initialState.copy(selectedIndex = 2)
    )
    assertEquals(result.selectedIndex, 0)
  }

  test("Quit returns exit command") {
    val (state, cmd) = TuiApp.update(Quit, initialState)
    assertEquals(cmd, Cmd.exit)
  }
}
```

#### `TuiViewTest.scala` -- Rendering snapshot tests

Tests that `view(state).render` produces expected ANSI output. Uses inline snapshots.

```scala
object TuiViewTest extends munit.FunSuite with SnapshotTest {

  val sampleProcesses = List(
    ScalaProcess(100, "sbt", 2048000L, Some(0L), 55, 7.3, "~/project"),
    ScalaProcess(200, "Bloop", 157000L, None, 35, 0.5, "~/.local/bloop")
  )

  test("view renders process table with selected row marker") {
    val state = TuiState(
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

    val rendered = TuiApp.view(state).render
    assert(rendered.contains("100"))           // PID present
    assert(rendered.contains("sbt"))            // type present
    assert(rendered.contains("2.0 MB"))         // RAM formatted
    assert(rendered.contains("7.3%"))           // MEM% present
    assert(rendered.contains("n/a"))            // swap None on macOS
    assert(rendered.contains("~/project"))      // project path
  }

  test("view renders status flash message") {
    val state = TuiState(
      processes = sampleProcesses,
      selectedIndex = 0,
      sortColumn = SortColumn.Ram,
      sortDescending = true,
      statusMessage = Some("SIGTERM sent to PID 100"),
      statusMessageExpiresAt = System.currentTimeMillis() + 3000,
      confirmation = ConfirmationKind.None,
      confirmTargetPid = None,
      tickFrame = 0
    )

    val rendered = TuiApp.view(state).render
    assert(rendered.contains("SIGTERM sent to PID 100"))
  }

  test("view renders confirmation overlay for SIGKILL") {
    val state = TuiState(
      processes = sampleProcesses,
      selectedIndex = 0,
      sortColumn = SortColumn.Ram,
      sortDescending = true,
      statusMessage = None,
      statusMessageExpiresAt = 0L,
      confirmation = ConfirmationKind.Sigkill,
      confirmTargetPid = Some(100),
      tickFrame = 0
    )

    val rendered = TuiApp.view(state).render
    assert(rendered.contains("Force kill"))
    assert(rendered.contains("100"))
    assert(rendered.contains("confirm"))
    assert(rendered.contains("cancel"))
  }

  test("view renders empty state gracefully") {
    val state = TuiState(
      processes = Nil,
      selectedIndex = 0,
      sortColumn = SortColumn.Ram,
      sortDescending = true,
      statusMessage = None,
      statusMessageExpiresAt = 0L,
      confirmation = ConfirmationKind.None,
      confirmTargetPid = None,
      tickFrame = 0
    )

    val rendered = TuiApp.view(state).render
    assert(rendered.length > 0)  // does not crash, renders something
    // Should show "No processes" or similar
  }

  test("view shows sort indicator") {
    val state = TuiState(
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

    val rendered = TuiApp.view(state).render
    assert(rendered.contains("Ram"))  // sort column shown in UI
  }
}
```

#### `ProcessActionsTest.scala` -- Action tests

```scala
object ProcessActionsTest extends munit.FunSuite {

  // Mocked actions -- no real processes harmed
  test("threadDump returns success message") {
    val result = ProcessActions.threadDump(12345)
    assertEquals(result, Right("Thread dump requested for PID 12345 -> /tmp/threads-12345.hprof"))
  }

  test("heapDump returns success message") {
    val result = ProcessActions.heapDump(12345)
    assertEquals(result, Right("Heap dump requested for PID 12345 -> /tmp/heap-12345.hprof"))
  }

  // Real kill -- only run manually, not in CI
  // test("sendSigterm works on real process") { ... }
  // test("sendSigkill works on real process") { ... }
}
```

#### `SortTest.scala` -- Sort logic tests

```scala
object SortTest extends munit.FunSuite {

  val procs = List(
    ScalaProcess(300, "sbt", 2048000L, Some(0L), 55, 7.3, "~/a"),
    ScalaProcess(100, "Metals", 698000L, None, 58, 2.2, "~/b"),
    ScalaProcess(200, "Bloop", 157000L, Some(0L), 35, 0.5, "~/c")
  )

  test("sort by PID ascending") {
    val result = TuiApp.sort(procs, SortColumn.Pid, ascending = true)
    assertEquals(result.map(_.pid), List(100, 200, 300))
  }

  test("sort by RAM descending") {
    val result = TuiApp.sort(procs, SortColumn.Ram, ascending = false)
    assertEquals(result.head.pid, 300)
    assertEquals(result.last.pid, 200)
  }

  test("sort by Kind groups by type") {
    val result = TuiApp.sort(procs, SortColumn.Kind, ascending = true)
    assertEquals(result.map(_.kind), List("Bloop", "Metals", "sbt"))
  }

  test("sort by MemPercent descending") {
    val result = TuiApp.sort(procs, SortColumn.MemPercent, ascending = false)
    assertEquals(result.head.kind, "sbt")
    assertEquals(result.last.kind, "Bloop")
  }
}
```

### Test execution strategy

#### CI (automated)

```bash
# Run all unit tests in JVM mode (fast, no native compile)
scala-cli test .
```

Tests to run in CI:
- `TuiStateTest` -- all state transitions
- `TuiViewTest` -- all rendering snapshots
- `ProcessActionsTest` -- mocked actions only
- `SortTest` -- sort logic

Tests NOT in CI:
- Real `sendSigterm`/`sendSigkill` -- requires real PID, destructive
- `LayoutzApp.run()` -- requires TTY
- Resize handling -- requires terminal emulator

#### Manual QA (per commit)

After each implementation commit, manual smoke test:

| Test | Steps | Expected |
|------|-------|----------|
| Launch TUI | `scala-monitor --watch` | TUI appears with process table, alternate screen |
| Navigate | Press `j`, `k`, arrows | Selection moves, wraps at edges |
| SIGTERM | Select process, press `d` | Status flash "SIGTERM sent to PID ..." |
| SIGKILL confirm | Select process, press `Ctrl+K` | Confirmation overlay appears |
| SIGKILL confirm | Press `Enter` | Process killed, status flash |
| SIGKILL cancel | Press `Ctrl+K`, then `Esc` | Overlay dismissed, no kill |
| Thread dump | Select process, press `t` | Status flash with mocked message |
| Heap dump | Select process, press `h` | Status flash with mocked message |
| Sort | Press `F` repeatedly | Column cycles, direction toggles |
| Quit | Press `q` | Terminal restored, back to normal |
| Ctrl+C quit | Press `Ctrl+C` | Terminal restored cleanly |
| One-shot unbroken | `scala-monitor` | Normal table output, unchanged |
| One-shot pid | `scala-monitor -o pid` | PID list, unchanged |

### Commit additions

#### C3.5: `test: add test helpers for TUI tests`

**Files**: `test/SnapshotTest.scala` (NEW)

Munit is already configured in `project.scala` and `test/PsOutputParsingSuite.scala` proves it works. Only need the snapshot helper trait for the new TUI test suites.

Create `test/SnapshotTest.scala` with `assertSnapshot` helper (10 LOC, as defined in Test Plan section).

**QA**: `scala-cli test .` -- existing tests still pass.

**Effort**: 5 min

#### C4.5: `test: add TUI state and rendering tests`

**Files**: `test/TuiStateTest.scala` (NEW), `test/TuiViewTest.scala` (NEW), `test/ProcessActionsTest.scala` (NEW), `test/SortTest.scala` (NEW)

Add test files in the `test/` directory alongside the existing `PsOutputParsingSuite.scala`.

- All unit tests from above
- **QA**: `scala-cli test .` -- all tests pass

### Updated effort estimate

| Commit | Description | Effort |
|--------|-------------|--------|
| C1 | Add layoutz dependency | 5 min |
| C2 | Fix macOS thread count | 20 min |
| C3 | ProcessActions (kill + mocked dumps) | 25 min |
| C3.5 | Add snapshot test helper | 5 min |
| C4 | TUI mode (--watch flag) | 60 min |
| C4.5 | TUI state + rendering tests | 30 min |
| C5 | Integration smoke test | 15 min |
| **Total** | | **~2.5 hours** |

### Updated dependency graph

```
C1 (layoutz dep) --------+
                         +--> C4 (TuiApp + ScalaMonitor) --> C4.5 (TUI tests) --> C5 (integration test)
C3 (ProcessActions) -----+                            |
C3.5 (munit infra) ----------------------------------+
C2 (macOS threads) ----------------------> (independent, anytime)
```

## Future Work (Out of Scope)

- Real thread dump implementation (`jcmd <pid> Thread.print -l > /tmp/...`)
- Real heap dump implementation (`jcmd <pid> GC.heap_dump /tmp/...`)
- Multi-select with `Space` for batch actions
- Signal picker overlay (choose arbitrary signal)
- Help overlay (`?` key)
- Adjustable refresh rate (`+`/`-` keys)
- Color-coded memory thresholds (green/yellow/red)
- Memory bar graphs per process
- Inline filter (`/` key)
- Per-platform column visibility (hide swap/threads on macOS)
- snapshot4s integration (if project ever migrates to sbt)
