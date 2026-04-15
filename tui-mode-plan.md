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
| SIGKILL | `K` -- confirmation overlay first |
| Thread dump | `t` -- mocked, return message only |
| Heap dump | `h` -- mocked, return message only |
| Navigation | Arrow keys + `j`/`k` (vim-style), `g`/`G` (first/last) |
| Sort | `F` -- cycle PID, TYPE, RAM, MEM%, PROJECT |
| Help | `?` -- toggle help overlay |
| Quit | `q` or `Ctrl+C` -- both clean exit |
| Alternate screen | Yes (layoutz `clearOnStart`/`clearOnExit`) |
| Refresh interval | 1 second |
| macOS threads | Fix via `proc_pidinfo` PROC_PIDTASKINFO |
| macOS swap | Show `n/a` (platform limitation, no API exposes per-process swap) |
| Kill UX | Hybrid -- `d` immediate SIGTERM + flash, `K` SIGKILL with confirmation prompt |

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
       |       +-- MacOsProbe.scala (MODIFY -- add ProcPidTaskInfo/ProcTaskInfoSize constants,
       |       |                        add readThreadCount, add threadCountResolver to parsePsLines)
       |       +-- MacOsExtern.scala (UNCHANGED -- @extern FFI bindings only)
       |
       +-- TuiApp.scala (NEW -- class TuiApp(debug) extends LayoutzApp[TuiState, TuiMsg])
       |       |
       |       +-- ProcessActions.scala (NEW -- signal.kill + mocked dumps)
       |
       +-- scripts/integration-test.sh (MODIFY -- add --watch and -o pid assertions)
       +-- test/ (MODIFY -- add TUI test suites alongside existing PsOutputParsingSuite)
```

## Screen Layout (Phase 2 Redesign)

Inspired by btop's compact title-bar-in-box pattern. All metadata (process count, total RAM, sort indicator) merged into the box title bar. Banner and info bar rows eliminated.

### SCREEN 1: Normal State

```
╭── SCALA PROCESS MONITOR — 3 processes — 3.1 GB RAM ─ RAM ▾ ──────╮
│ [Bold Cyan]  PID      TYPE      RAM      SWAP   MEM%  THR  PROJECT  [/Bold Cyan]     │
│  2513087  [Magenta]sbt[/Magenta]     2.3 GB    0 kB  [Red]7.3%[/Red]   55  ~/Code/smithy4s        │
│▶[BlueBg][Bold] 2503995  [Blue]Metals[/Blue]   [Yellow]698 MB[/Yellow]  n/a   [Yellow]2.2%[/Yellow]   58  ~/Code/scala-monitor[/Bold][/BlueBg]   │
│  2647652  [Green]Bloop[/Green]   [Green]157 MB[/Green]  234 MB  [Green]0.5%[/Green]   35  ~/.local/.../bloop     │
╰───────────────────────────────────────────────────────────────────────╯
[Dim] ↑↓ jk  d term  K kill  t threads  h heap  F sort  ? help  q quit
```

- **No separate banner/info rows** — all metadata in the box title bar
- **Title bar**: process count, total RAM, sort column + direction indicator (▾ descending, ▴ ascending)
- **Selected row**: blue background (`Color.True(30, 60, 90)`) + bold, with per-cell colors preserved
- **No `>` marker** — selected row distinguished by background color alone

### Color Reference (Phase 2)

| Element | Color | Detail |
|---------|-------|--------|
| Table headers | Bold Cyan | `Color.Cyan` + `Style.Bold` |
| Selected row bg | Blue | `Color.True(30, 60, 90)` + `Style.Bold` |
| sbt | Magenta | `Color.Magenta` |
| Metals | Blue | `Color.Blue` |
| Bloop | Green | `Color.Green` |
| scala-cli | Cyan | `Color.Cyan` |
| scalac | Yellow | `Color.Yellow` |
| Other types | White | default |
| RSS < 0.5 GB | Green | `Color.Green` |
| RSS 0.5–1 GB | Yellow | `Color.Yellow` |
| RSS > 1 GB | Red | `Color.Red` |
| MEM% < 2% | Green | `Color.Green` |
| MEM% 2–5% | Yellow | `Color.Yellow` |
| MEM% > 5% | Red | `Color.Red` |
| Status flash (success) | Green | `✓` prefix |
| Status flash (error) | Red | `✗` prefix |
| Status flash (dump) | Yellow | `⏳` prefix |
| Footer | Dim | `Color.BrightBlack` |
| Confirmation overlay | Red | border + text |
| Table border | Default | white |

### SCREEN 2: SIGTERM Flash (after pressing `d`)

```
╭── SCALA PROCESS MONITOR — 3 processes — 3.1 GB RAM ─ RAM ▾ ──────╮
│  PID      TYPE      RAM      SWAP   MEM%  THR  PROJECT              │
│  2513087  sbt       2.3 GB    0 kB  7.3%   55  ~/Code/smithy4s       │
│  2503995  Metals    698 MB    n/a   2.2%   58  ~/Code/scala-monitor  │
│  2647652  Bloop     157 MB    234 MB  0.5%  35  ~/.local/.../bloop   │
╰───────────────────────────────────────────────────────────────────────╯
[Dim] ↑↓ jk  d term  K kill  t threads  h heap  F sort  ? help  q quit
[Green] ✓ SIGTERM sent to PID 2503995 (Metals)[/Green]
```

### SCREEN 3: SIGKILL Confirmation Overlay (after pressing `K`)

```
╭── SCALA PROCESS MONITOR — 3 processes — 3.1 GB RAM ─ RAM ▾ ──────╮
│  PID      TYPE      RAM      SWAP   MEM%  THR  PROJECT              │
│  2513087  sbt       2.3 GB    0 kB  7.3%   55  ~/Code/smithy4s       │
│  2503995  Metals    698 MB    n/a   2.2%   58  ~/Code/scala-monitor  │
│  2647652  Bloop     157 MB    234 MB  0.5%  35  ~/.local/.../bloop   │
╰───────────────────────────────────────────────────────────────────────╯
[Red] ╭─!! Force kill Metals (PID 2503995)?  Enter=confirm  Esc=cancel─╮
[Red] ╰────────────────────────────────────────────────────────────────╯
```

- Shows process kind + PID in confirmation
- Footer remains visible below overlay

### SCREEN 4: Help Overlay (after pressing `?`)

```
╭── SCALA PROCESS MONITOR — 3 processes — 3.1 GB RAM ─ RAM ▾ ──────╮
│  PID      TYPE      RAM      SWAP   MEM%  THR  PROJECT              │
│  ...                                                             │
╰───────────────────────────────────────────────────────────────────────╯
╭── Help ──────────────────────────────────────────────────────────╮
│  Navigation    ↑↓ jk  move    g first    G last                 │
│  Actions       d term  K kill  t threads  h heap                 │
│  Display       F sort  ? help                                   │
│  Quit          q exit                                          │
╰───────────────────────────────────────────────────────────────────╯
```

### SCREEN 5: Empty State (no Scala processes)

```
╭── SCALA PROCESS MONITOR — 0 processes — 0 kB RAM ────────────────╮
│                                                                   │
│         No Scala processes found.                                 │
│         Press q to quit or wait for processes to appear.          │
│                                                                   │
╰───────────────────────────────────────────────────────────────────╯
[Dim] ↑↓ jk  d term  K kill  t threads  h heap  F sort  ? help  q quit
```

## Keybindings

| Key | Action |
|-----|--------|
| `Up` / `k` | Move selection up |
| `Down` / `j` | Move selection down |
| `g` | Jump to first process |
| `G` | Jump to last process |
| `d` | Send SIGTERM to selected (immediate, status flash) |
| `K` | Send SIGKILL to selected (confirmation overlay first) |
| `t` | Thread dump selected (mocked) |
| `h` | Heap dump selected (mocked) |
| `F` | Cycle sort column (PID -> TYPE -> RAM -> MEM% -> PROJECT) |
| `?` | Toggle help overlay |
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
  tickFrame: Int,                      // animation frame counter
  showHelp: Boolean = false            // help overlay toggle (Phase 2)
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
case object RequestSigkill extends TuiMsg             // K key
case object ConfirmKill extends TuiMsg                // Enter on overlay
case object CancelConfirmation extends TuiMsg         // Esc on overlay
case object RequestThreadDump extends TuiMsg          // t key
case object RequestHeapDump extends TuiMsg            // h key
case class ActionCompleted(msg: String) extends TuiMsg
case class ActionFailed(err: String) extends TuiMsg
case object ClearStatus extends TuiMsg
case object TickFrame extends TuiMsg                  // animation tick (100ms)
case object Quit extends TuiMsg                       // q key
case object ToggleHelp extends TuiMsg                 // ? key (Phase 2)
case object JumpToFirst extends TuiMsg                // g key (Phase 2)
case object JumpToLast extends TuiMsg                 // G key (Phase 2)
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

**Files**: `MacOsProbe.scala` (MODIFY)

**MacOsExtern.scala** — NO net change. The `@extern object libproc` cannot hold Scala `val` constants (only `def` with `= extern` is valid). Constants moved to `MacOsProbe` class instead.

**MacOsProbe.scala** -- add `ProcPidTaskInfo` and `ProcTaskInfoSize` as private vals on the class (alongside existing `ProcPidVnodePathInfo`, `VnodeInfoStructSize`), add `readThreadCount()` private method, and add `threadCountResolver` parameter to `parsePsLines`.

```scala
class MacOsProbe(debug: Debug) extends PlatformProbe {
  private val ProcPidVnodePathInfo = 9
  private val VnodeInfoCwdPathOffset = 152
  private val VnodeInfoStructSize = 2352
  private val ProcPidTaskInfo = 4
  private val ProcTaskInfoSize = 96
  // ...

  private def readThreadCount(pid: Int): Int = Zone.acquire { implicit z =>
    val buffer = alloc[Byte](ProcTaskInfoSize)
    val bytesWritten = libproc.proc_pidinfo(pid, ProcPidTaskInfo, 0L, buffer, ProcTaskInfoSize)
    if (bytesWritten <= 0) 0
    else {
      val ptr = (buffer + 84).asInstanceOf[Ptr[CInt]]
      !ptr
    }
  }

  def discover(selfPid: Int): List[ScalaProcess] = {
    val lines = runPsCommand()
    MacOsProbe.parsePsLines(
      lines, selfPid, debug,
      cwdResolver = readProcessWorkingDirectory,
      threadCountResolver = readThreadCount
    )
  }
}

object MacOsProbe {
  def parsePsLines(
    lines: List[String],
    selfPid: Int,
    debug: Debug,
    cwdResolver: Int => Option[String],
    threadCountResolver: Int => Int = _ => 0   // backward compat for tests
  ): List[ScalaProcess] = {
    // ... threads = threadCountResolver(pid) instead of threads = 0
  }
}
```

**NOTE on pointer dereference**: `!ptr` dereferences the pointer (Scala Native's `!` is the dereference operator, not logical NOT). This is correct — it reads the `CInt` value at the pointer address. For `Ptr[CInt]`, `!ptr` returns `Int`. Do NOT write `(!ptr).toInt` (redundant) or `!ptr.toInt` (won't compile — `.toInt` doesn't exist on `CInt`).

**QA**: Build on macOS, run, verify THR column shows real values (not 0). Existing `PsOutputParsingSuite` should still pass (it uses `noCwd` resolver, thread resolver defaults to `_ => 0`).

**Effort**: 25 min

---

### C3: `feat: add ProcessActions (kill + mocked dumps)`

**Files**: `ProcessActions.scala` (NEW)

```scala
package org.polyvariant

import scala.scalanative.posix.signal

object ProcessActions {

  def sendSigterm(pid: Int): Either[String, String] = {
    val result = signal.kill(pid, signal.SIGTERM)
    if (result == 0) Right(s"SIGTERM sent to PID $pid")
    else Left(s"kill($pid, SIGTERM) failed")
  }

  def sendSigkill(pid: Int): Either[String, String] = {
    val result = signal.kill(pid, signal.SIGKILL)
    if (result == 0) Right(s"SIGKILL sent to PID $pid")
    else Left(s"kill($pid, SIGKILL) failed")
  }

  def threadDump(pid: Int): Either[String, String] = {
    Right(s"Thread dump requested for PID $pid -> /tmp/threads-$pid.hprof")
  }

  def heapDump(pid: Int): Either[String, String] = {
    Right(s"Heap dump requested for PID $pid -> /tmp/heap-$pid.hprof")
  }
}
```

**NOTE**: `kill()` is in `scala.scalanative.posix.signal`, NOT `scala.scalanative.posix.unistd`. This is a Scala Native 0.5.10 quirk — `unistd.kill` does not exist.

**QA**: Compiles. Integration: manually test `sendSigterm` against a real process.

**Effort**: 25 min

---

### C4: `feat: add TUI mode (--watch flag)`

**Files**: `TuiApp.scala` (NEW), `ScalaMonitor.scala` (MODIFY)

#### TuiApp.scala (~250 LOC)

Extends `LayoutzApp[TuiState, TuiMsg]` from layoutz.

**init**: Call `ScalaMonitor.discover(debug)`, return initial state with processes sorted by RAM descending.

**IMPORTANT**: layoutz's `update` returns `(State, Cmd[Message])`, not bare state. Inside `update`, an implicit converts bare `State` to `(State, Cmd.none)`. But external callers always receive the tuple. All test code must destructure: `val (result, _) = TuiApp.update(msg, state)`.

**update** -- key message handlers:

| Message | Behavior |
|---------|----------|
| `RefreshProcesses` | Fire `Cmd.task { discover() }` off-thread, result as `ProcessesLoaded` |
| `ProcessesLoaded(procs)` | Sort and replace `state.processes`. Reset `selectedIndex` if out of bounds |
| `MoveUp` | `selectedIndex = max(0, current - 1)` |
| `MoveDown` | `selectedIndex = min(size - 1, current + 1)` |
| `SortCycle` | Cycle to next sort column (PID→TYPE→RAM→MEM%→PROJECT), always descending |
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
val confirming = state.confirmation != ConfirmationKind.None
Sub.batch(
  Sub.time.everyMs(1000, RefreshProcesses),
  Sub.time.everyMs(100, TickFrame),
  Sub.onKeyPress {
    case Key.Char('q')                     => Some(Quit)
    case Key.Up | Key.Char('k')             => Some(MoveUp)
    case Key.Down | Key.Char('j')           => Some(MoveDown)
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
  val app = new TuiApp()  // concrete instance of LayoutzApp
  app.run(
    tickIntervalMs = 100,
    renderIntervalMs = 50,
    clearOnStart = true,
    clearOnExit = true,
    showQuitMessage = false,
    quitKey = Key.Ctrl('C')
  )
}
```

**NOTE**: `LayoutzApp.run(tickIntervalMs, ...)` is inherited. The companion `run(debug)` calls it on the instance via `app.run(...)`. The two methods have different parameter types so overloading resolves correctly.

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

| Risk | Probability | Impact | Status |
|------|-------------|--------|--------|
| layoutz SN 0.5.10 incompatibility | Medium | High | **RESOLVED** — compiles and links cleanly on first try |
| macOS proc_taskinfo struct offset wrong | Low | Medium | **RESOLVED** — `pti_threadnum` at offset 84, struct size 96, verified via XNU headers |
| Blocking refresh freezes TUI | Low | Medium | **RESOLVED** — `Cmd.task` runs off-thread. Not yet manually verified under load |
| Terminal too narrow for table | Medium | Low | **KNOWN** — columns are fixed-width, no responsive layout yet |
| Ctrl+C handling | Low | Low | **RESOLVED** — layoutz `quitKey = Key.Ctrl('C')` intercepts at raw input level |
| `posix.signal.kill` import path | Low | Low | **RESOLVED** — `kill` is in `signal`, not `unistd` (SN 0.5.10 quirk) |
| Sort state lost on refresh | Low | Low | **RESOLVED** — sort is in TuiState, preserved across refreshes |
| `@extern` objects can't hold Scala vals | — | — | **DISCOVERED** — moved FFI constants to consuming class |
| layoutz `table()` type mismatch | — | — | **DISCOVERED** — requires `Seq[Element]`, not `Seq[String]` |
| layoutz has no `text()` function | — | — | **DISCOVERED** — use implicit `stringToText` or `(s: Element)` |
| SN test discovery requires `class` not `object` | — | — | **DISCOVERED** — munit Native needs `@EnableReflectiveInstantiation` + instantiable class |

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

#### Custom snapshot helper (9 LOC)

In `test/SnapshotTest.scala`:

```scala
package org.polyvariant

trait SnapshotTest {
  self: munit.FunSuite =>

  def assertSnapshot(obtained: String, expected: String)(implicit loc: munit.Location): Unit =
    assertEquals(obtained.stripMargin.trim, expected.stripMargin.trim)
}
```

The `assertFileSnapshot` method was planned but dropped — not needed for 5 simple rendering tests.

#### Running tests

```bash
# Run all tests (Scala Native mode — compiles + links native binary, ~25s)
scala-cli test .
```

**IMPORTANT**: Tests run as Scala Native binaries, not JVM. `project.scala` has `//> using platform native` which applies to both main and test sources. There is no JVM test override. This means each test run takes ~25s for native linking. The `--test-only` flag is not supported by Scala Native's test runner.

The TUI logic (state transitions, sort, mocked actions) is pure Scala with no native FFI dependencies — it *could* run on JVM if a `//> using test.platform jvm` override were added. But layoutz's `LayoutzApp` trait depends on native terminal I/O, so any test importing `layoutz._` must compile to native.

### Test files

#### `TuiStateTest.scala` -- State transition tests

Tests the pure `update(msg, state) -> (State, Cmd[Msg])` function for every message type.

**NOTE**: `LayoutzApp.update` returns `(State, Cmd[Message])`, not bare `State`. All tests must destructure the tuple. The implicit conversion `(State) => (State, Cmd.none)` only works inside the `update` implementation itself.

```scala
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
    sortDescending = true,
    statusMessage = None,
    statusMessageExpiresAt = 0L,
    confirmation = ConfirmationKind.None,
    confirmTargetPid = None,
    tickFrame = 0
  )

  private def updateState(msg: TuiMsg, state: TuiState = initialState): TuiState =
    (new TuiApp(false)).update(msg, state)._1

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

  test("SortCycle changes sort column and resets to descending") {
    val sorted = initialState.copy(sortColumn = SortColumn.Ram, sortDescending = true)
    val result = updateState(SortCycle, sorted)
    assertNotEquals(result.sortColumn, SortColumn.Ram)
    assertEquals(result.sortDescending, true)
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
    val (_, cmd) = (new TuiApp(false)).update(Quit, initialState)
    assertEquals(cmd, Cmd.exit)
  }
}
```

#### `TuiViewTest.scala` -- Rendering snapshot tests

Tests that `view(state).render` produces expected ANSI output. Uses inline snapshots.

```scala
class TuiViewTest extends munit.FunSuite with SnapshotTest {

  val sampleProcesses = List(
    ScalaProcess(100, "sbt", 2048000L, Some(0L), 55, 7.3, "~/project"),
    ScalaProcess(200, "Bloop", 157000L, None, 35, 0.5, "~/.local/bloop")
  )

  private def viewRender(state: TuiState): String =
    (new TuiApp(false)).view(state).render

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

    val rendered = viewRender(baseState)
    assert(rendered.contains("100"))           // PID present
    assert(rendered.contains("sbt"))            // type present
    assert(rendered.contains("2.0 GB"))         // RAM formatted (2048000 KB = ~2 GB)
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

    val rendered = viewRender(state)
    assert(rendered.contains("SIGTERM sent to PID 100"))
  }

  test("view renders confirmation overlay for SIGKILL") {
    val state = baseState.copy(
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

    val rendered = viewRender(state)
    assert(rendered.contains("Force kill"))
    assert(rendered.contains("100"))
    assert(rendered.contains("confirm"))
    assert(rendered.contains("cancel"))
  }

  test("view renders empty state gracefully") {
    val state = baseState.copy(processes = Nil)
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

    val rendered = viewRender(state)
    assert(rendered.length > 0)  // does not crash, renders something
  }

  test("view shows sort indicator") {
    val rendered = viewRender(baseState)
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

    assert(rendered.contains("Ram"))  // sort column shown in UI
  }
}
```

#### `ProcessActionsTest.scala` — Action tests

```scala
class ProcessActionsTest extends munit.FunSuite {

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

Tests the public `TuiApp.sort` method. This must be defined on `TuiApp` as a standalone function (not inside `update`) so it can be reused by both `update(SortCycle)` and tests.

```scala
class SortTest extends munit.FunSuite {

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

**Implementation**: `TuiApp.sort` is a public method on the companion object:

```scala
def sort(procs: List[ScalaProcess], column: SortColumn, ascending: Boolean): List[ScalaProcess] = {
  val ordering = column match {
    case SortColumn.Pid       => Ordering.by[ScalaProcess, Int](_.pid)
    case SortColumn.Kind      => Ordering.by[ScalaProcess, String](_.kind)
    case SortColumn.Ram       => Ordering.by[ScalaProcess, Long](_.ramKb)
    case SortColumn.MemPercent => Ordering.by[ScalaProcess, Double](_.memPercent)
    case SortColumn.Project   => Ordering.by[ScalaProcess, String](_.projectPath)
  }
  val sorted = procs.sorted(using ordering)
  if (ascending) sorted else sorted.reverse
}
```

Note: `Ordering.by` returns `Ordering[T]` which is inferred. The `using` keyword is required by Scala 3.8.x for implicit parameters (suppresses a warning).

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

## Implementation Status: COMPLETE (Phase 1 + Phase 2)

### Phase 1: Core TUI — COMPLETE

All commits implemented and verified. Build passes, 38/38 tests pass, one-shot mode unchanged.

### Phase 2: Visual Redesign + Main Class Fallback — COMPLETE

UI/UX research (7 background agents, btop/bottom/lazygit analysis) led to a complete visual overhaul of the TUI. Also implemented GitHub issue #5 (main class fallback for unclassified processes).

#### Changes summary

| Area | Change |
|------|--------|
| **Layout** | Merged banner + info bar into single `box("title")(...)` title bar. Removed separate rows. |
| **Selected row** | Blue background (`Color.True(30, 60, 90)`) + bold. Per-cell colors preserved for TYPE/RSS/MEM%. |
| **Per-type color** | sbt=Magenta, metals=Blue, bloop=Green, scala-cli=Cyan, scalac=Yellow, other=White |
| **RSS gradient** | <0.5 GB=Green, 0.5–1 GB=Yellow, >1 GB=Red |
| **MEM% gradient** | <2%=Green, 2–5%=Yellow, >5%=Red |
| **Table headers** | Bold cyan |
| **Status flash** | Colored by outcome: ✓ Green (success), ✗ Red (error), ⏳ Yellow (dump) |
| **Footer** | Compact single line: `↑↓ jk  d term  K kill  t threads  h heap  F sort  ? help  q quit` |
| **Help overlay** | `?` key toggles categorized keybinding overlay |
| **Jump keys** | `g` = jump to first, `G` = jump to last |
| **Empty state** | Helpful message instead of blank table |
| **Sort bug** | Fixed inverted sort direction on process refresh |
| **Main class fallback** | `extractMainClass()` extracts FQN from cmdline when classify() finds no known pattern (issue #5) |

#### New/modified files

| File | Change |
|------|--------|
| `ScalaMonitor.scala` | Added `extractMainClass()`, modified `classify()` fallback chain |
| `TuiApp.scala` | Complete `view()` rewrite, added `showHelp`/`ToggleHelp`/`JumpToFirst`/`JumpToLast`, fixed sort bug |
| `test/PsOutputParsingSuite.scala` | 2 tests updated + 6 new tests for extractMainClass/classify fallback |
| `test/TuiViewTest.scala` | Rewritten: 5 tests → 8 tests for new rendering |
| `test/TuiStateTest.scala` | 6 new tests: ToggleHelp ×2, JumpToFirst ×2, JumpToLast ×2 |

### Verification evidence (Phase 2)

```
$ scala-cli --power package . -o scala-monitor -f
[info] Total (26073 ms)
Wrote ./scala-monitor

$ scala-cli test .
Test run org.polyvariant.TuiViewTest finished: 0 failed, 0 ignored, 8 total
Test run org.polyvariant.ProcessActionsTest finished: 0 failed, 0 ignored, 2 total
Test run org.polyvariant.PsOutputParsingSuite finished: 0 failed, 0 ignored, 17 total
Test run org.polyvariant.SortTest finished: 0 failed, 0 ignored, 4 total
Test run org.polyvariant.TuiStateTest finished: 0 failed, 0 ignored, 22 total
(53 tests total, 0 failures)

$ ./scala-monitor --help
  -w --watch         Interactive TUI mode (like top)

$ ./scala-monitor
  SCALA PROCESS MONITOR  —  4 processes  —  Total Memory: 4.0 GB
  ... (unchanged one-shot output)
```

### Deviations from Plan

Every deviation is documented with rationale. No arbitrary changes were made.

#### 1. `kill()` is in `signal`, not `unistd` (C3)

**Plan said**: `unistd.kill(pid, signal.SIGTERM)`
**Reality**: Scala Native 0.5.10 does not expose `unistd.kill`. The `kill()` function is in `scala.scalanative.posix.signal`.

```scala
// BEFORE (plan):
import scala.scalanative.posix.signal
import scala.scalanative.posix.unistd
val result = unistd.kill(pid, signal.SIGTERM)

// AFTER (actual):
import scala.scalanative.posix.signal
val result = signal.kill(pid, signal.SIGTERM)
```

#### 2. `@extern` objects cannot hold Scala `val` constants (C2)

**Plan said**: Add `private val ProcPidTaskInfo = 4` and `private val ProcTaskInfoSize = 96` inside the `@extern object libproc`.
**Reality**: Scala Native's `@extern` objects are FFI bindings — only `def` with `= extern` are valid members. Scala `val` constants are not supported inside `@extern` objects and won't compile.

**Fix**: Moved the constants to `MacOsProbe` class (which is in the same package and already holds similar constants like `ProcPidVnodePathInfo`, `VnodeInfoStructSize`).

```scala
// BEFORE (plan — won't compile):
@extern object libproc {
  def proc_pidinfo(...): Int = extern
  private val ProcPidTaskInfo = 4      // ERROR
  private val ProcTaskInfoSize = 96    // ERROR
}

// AFTER (actual):
@extern object libproc {
  def proc_pidinfo(...): Int = extern
}

class MacOsProbe(debug: Debug) extends PlatformProbe {
  private val ProcPidTaskInfo = 4
  private val ProcTaskInfoSize = 96
  // ... readThreadCount() uses these
}
```

#### 3. `LayoutzApp` must be a `class`, not `object` (C4)

**Plan said**: `object TuiApp extends LayoutzApp[TuiState, TuiMsg]`
**Reality**: layoutz's `LayoutzApp.run(...)` is a concrete instance method, not a static method. An `object` extending a trait with instance methods works in Scala, but the `init` method needs access to constructor params (`debug: Boolean`). The `LayoutzApp` trait has no constructor params, so `debug` must be on the concrete class. Using a `class` with a companion `object run(debug)` method cleanly separates the entry point from the app instance.

```scala
// AFTER (actual):
class TuiApp(debug: Boolean) extends LayoutzApp[TuiState, TuiMsg] {
  def init: (TuiState, Cmd[TuiMsg]) = {
    val procs = ScalaMonitor.discover(debug)  // uses constructor param
    ...
  }
  // ...
}

object TuiApp {
  def run(debug: Boolean): Unit = {
    val app = new TuiApp(debug)
    app.run(tickIntervalMs = 100, renderIntervalMs = 50, ...)
  }
  def sort(...): List[ScalaProcess] = { ... }  // public for testing
}
```

#### 4. `Cmd.task` returns `Cmd`, not `(State, Cmd)` — must wrap explicitly (C4)

**Plan said**: `RefreshProcesses` case in `update` can return `Cmd.task(...)` directly (relying on implicit).
**Reality**: `update` must return `(State, Cmd[Msg])`. The implicit `state => (state, Cmd.none)` only converts bare `State`, not `Cmd`. When `RefreshProcesses` needs to return a `Cmd` without changing state, you must explicitly return `(state, Cmd.task(...))`.

```scala
// BEFORE (plan — type error):
case RefreshProcesses =>
  Cmd.task(ScalaMonitor.discover(debug)) { ... }
  // error: Found Cmd[TuiMsg], Required (TuiState, Cmd[TuiMsg])

// AFTER (actual):
case RefreshProcesses =>
  (state, Cmd.task(ScalaMonitor.discover(debug)) { ... })
```

#### 5. layoutz `table()` takes `Seq[Element]`, not `Seq[String]` (C4)

**Plan said**: `table(tableHeaders, tableRows)` with `List[String]`
**Reality**: layoutz's `table` function signature is `table(headers: Seq[Element], rows: Seq[Seq[Element]])`. Strings must be explicitly upcast via `s: Element` or `Text(s)`.

```scala
// BEFORE (plan — type error):
table(tableHeaders, tableRows)

// AFTER (actual):
val tableHeadersE: Seq[Element] = tableHeaders.map(h => h: Element)
val tableRowsE: Seq[Seq[Element]] = tableRows.map(row => row.map(s => s: Element))
table(tableHeadersE, tableRowsE)
```

#### 6. layoutz has no `text()` function — use string-to-Element implicit (C4)

**Plan said**: `text(footerText).color(Color.BrightBlack)`
**Reality**: layoutz has no standalone `text()` function. Strings are implicitly converted to `Element` via `implicit def stringToText(s: String): Text`. Use `(s: Element)` or just `s` where the type is already `Element`.

```scala
// BEFORE (plan — error: Not found: text):
val footer = text(footerText).color(Color.BrightBlack)

// AFTER (actual):
val footer = (footerText: Element).color(Color.BrightBlack)
```

#### 7. SortCycle always cycles + always descending (C4, C4.5)

**Plan said**: "Cycle sort column. Toggle direction if same column pressed again" with a test asserting `sortDescending = false` after SortCycle.
**Reality**: These were contradictory. SortCycle cycles through 5 distinct columns (PID→TYPE→RAM→MEM%→PROJECT), so pressing the same column again requires 5 presses — not a useful UX pattern. The plan's own test asserted the column *changed* (`assertNotEquals(result.sortColumn, SortColumn.Ram)`) which conflicts with "toggle on same column". Implemented: always cycle to next column, always set descending.

```scala
// AFTER (actual):
case SortCycle =>
  val columns = List(SortColumn.Pid, SortColumn.Kind, SortColumn.Ram, SortColumn.MemPercent, SortColumn.Project)
  val nextIdx = (columns.indexOf(state.sortColumn) + 1) % columns.size
  val sorted = TuiApp.sort(state.processes, columns(nextIdx), ascending = false)
  state.copy(processes = sorted, sortColumn = columns(nextIdx), sortDescending = true)
```

Test updated to match:
```scala
test("SortCycle changes sort column and resets to descending") {
  val result = updateState(SortCycle, initialState.copy(sortColumn = SortColumn.Ram, sortDescending = true))
  assertNotEquals(result.sortColumn, SortColumn.Ram)
  assertEquals(result.sortDescending, true)
}
```

#### 8. Test suites must be `class`, not `object` (C4.5)

**Plan said**: `object TuiStateTest extends munit.FunSuite`
**Reality**: Scala Native test discovery (via `@EnableReflectiveInstantiation` + `Reflect.lookupInstantiatableClass`) requires test suites to be instantiable classes, not singleton objects. `object` definitions compile and link fine but are not discovered by the test runner. The existing `PsOutputParsingSuite` already used `class` — new test files must follow the same convention.

```scala
// BEFORE (plan — not discovered by test runner):
object TuiStateTest extends munit.FunSuite { ... }

// AFTER (actual):
class TuiStateTest extends munit.FunSuite { ... }
```

#### 9. Tests run as Scala Native, not JVM (C4.5)

**Plan said**: "Tests run in JVM mode by default (fast, no native compilation needed)"
**Reality**: `project.scala` has `//> using platform native` which applies to both main and test sources. There is no `//> using test.platform jvm` override. All tests compile and run as native binaries (~25s link time). The `--test-only` flag is also not supported by Scala Native's test runner. This is a significant CI time consideration.

#### 10. `ProcTaskInfoSize` constant moved to `MacOsProbe` class (C2)

See deviation #2 above. The `MacOsExtern.scala` file has no net change from pre-implementation state — it remains a pure `@extern` FFI binding file with just `proc_pidinfo`, `popen`, and `pclose`.

### Phase 2 Deviations

#### 11. SIGKILL key changed from `Ctrl+K` to `K` (Phase 2)

**Phase 1 plan**: `Ctrl+K` triggers SIGKILL confirmation
**Phase 2 change**: Changed to plain `K` (uppercase) to free up Ctrl+K and avoid terminal compatibility issues. The compact footer already lists `K kill`.

#### 12. Sort direction bug in `ProcessesLoaded` handler (Phase 2)

**Found**: `TuiApp.scala:95` — `!state.sortDescending` was negating sort direction on every process refresh, causing the sort to flip between ascending/descending every second.
**Fixed**: Changed to `state.sortDescending`.

#### 13. layoutz `BgColored` has no `HasBorder` instance (Phase 2)

**Discovered during view() rewrite**: Cannot chain `.bg(...).border(...)` because `BgColored` doesn't implement `HasBorder`. Must apply `.border()` on the container (Box/Table) and `.bg()` only on inner cell Elements. The view() implementation correctly applies borders on `box(...)` and background colors on individual cells.

#### 14. `identity[Element]` for non-selected cell styling (Phase 2)

Used `identity[Element]` as the no-op styling function for non-selected rows. Valid Scala 3 syntax — `identity[A]` returns `A => A`.

### layoutz API findings (reference for future work)

Key API details discovered during implementation that aren't in the README:

| API | Detail |
|-----|--------|
| `LayoutzApp` | Trait with 4 abstract methods: `init`, `update`, `view`, `subscriptions`. `run(...)` is a concrete method with params: `tickIntervalMs`, `renderIntervalMs`, `quitKey`, `showQuitMessage`, `quitMessage`, `clearOnStart`, `clearOnExit`, `alignment`, `terminal`, `executionContext` |
| `update` return type | `(State, Cmd[Message])`. Implicit converts bare `State` to `(State, Cmd.none)` inside `update` body only |
| `Cmd.task[A, Msg]` | `def task(run: => A)(toMsg: Either[String, A] => Msg): Cmd[Msg]` — wraps in `Try`, maps to `Either` |
| `Cmd.exit` | Returns a `Cmd[Nothing]` that triggers clean shutdown |
| `Sub.onKeyPress` | `def onKeyPress(handler: Key => Option[Msg]): Sub[Msg]` — handler is re-evaluated on each keypress with fresh state |
| `Sub.time.everyMs` | `def everyMs(intervalMs: Long, msg: Msg): Sub[Msg]` — requires explicit `Long` literal (use `1000L`) |
| `table` | `table(headers: Seq[Element], rows: Seq[Seq[Element]]): Element` — NOT `Seq[String]` |
| `statusCard` | `statusCard(label: Element, content: Element): StatusCard` — strings implicitly convert |
| `box` | `box(title: String)(elements: Element*): Box` — title is `String`, body is varargs `Element` |
| `banner` | `banner(content: Element): Banner` — single `Element` arg, not `String` |
| `row` | `row(elements: Element*): Row` — varargs |
| `layout` | `layout(elements: Element*): Layout` — vertical stacking |
| `Key` | `Char(c)`, `Ctrl(c)`, `Up`, `Down`, `Enter`, `Escape`, `Tab`, `Backspace`, `Delete`, `Left`, `Right`, `Home`, `End`, `PageUp`, `PageDown`, `Unknown(code)` |
| `Color` | `NoColor`, `Black`..`White`, `BrightBlack`..`BrightWhite`, `Full(code)`, `True(r,g,b)` |
| `Border` | `None`, `Single`, `Double`, `Round`, `Thick`, `Ascii`, `Block`, `Dashed`, `Dotted`, `InnerHalfBlock`, `OuterHalfBlock`, `Markdown`, `Custom(corner, horizontal, vertical)` |
| String → Element | Implicit conversion: `implicit def stringToText(s: String): Text` — just use strings where `Element` is expected |

### Files changed (final inventory)

| File | Action | LOC |
|------|--------|-----|
| `project.scala` | MODIFIED — added layoutz dep | 8 |
| `MacOsExtern.scala` | UNCHANGED (constants moved to MacOsProbe) | 14 |
| `MacOsProbe.scala` | MODIFIED — added `readThreadCount`, `threadCountResolver` param | ~130 |
| `ScalaMonitor.scala` | MODIFIED — `--watch` flag, public `discover`/`formatMemory`, `extractMainClass` | ~212 |
| `PlatformProbe.scala` | UNCHANGED | 15 |
| `LinuxProbe.scala` | UNCHANGED | 122 |
| `Debug.scala` | UNCHANGED | 6 |
| `TuiApp.scala` | NEW — full TUI with state, update, view, subscriptions (Phase 2: view rewrite, help, jump keys) | ~340 |
| `ProcessActions.scala` | NEW — kill + mocked dumps | 27 |
| `test/SnapshotTest.scala` | NEW — assertSnapshot helper | 9 |
| `test/TuiStateTest.scala` | MODIFIED — 22 tests (Phase 2: +6 ToggleHelp/Jump tests) | 155 |
| `test/TuiViewTest.scala` | MODIFIED — 8 rendering tests (Phase 2: rewritten for new layout) | ~80 |
| `test/ProcessActionsTest.scala` | UNCHANGED — 2 mocked action tests | 14 |
| `test/SortTest.scala` | UNCHANGED — 4 sort logic tests | 32 |
| `test/PsOutputParsingSuite.scala` | MODIFIED — 17 tests (Phase 2: +6 extractMainClass/classify tests) | ~120 |
| `scripts/integration-test.sh` | MODIFIED — added --watch and -o pid assertions | ~115 |

## Future Work (Out of Scope)

- Real thread dump implementation (`jcmd <pid> Thread.print -l > /tmp/...`)
- Real heap dump implementation (`jcmd <pid> GC.heap_dump /tmp/...`)
- Multi-select with `Space` for batch actions
- Signal picker overlay (choose arbitrary signal)
- Adjustable refresh rate (`+`/`-` keys)
- Memory bar graphs per process
- Inline filter (`/` key)
- Per-platform column visibility (hide swap/threads on macOS)
- snapshot4s integration (if project ever migrates to sbt)
- Truncate long main class names in TYPE column (currently shows full FQN from `extractMainClass`)

## Main Class Fallback (GitHub Issue #5)

When `classify()` finds no known pattern (sbt, metals, bloop, etc.), it now extracts the JVM main class from the cmdline as a fallback before resorting to generic "Scala/JVM".

```scala
def extractMainClass(cmdline: String): Option[String] = {
  val tokens = cmdline.split("\\s+").drop(1)
  tokens.find { t =>
    t.contains(".") &&
    !t.startsWith("-") &&
    !t.contains("/") &&
    !t.endsWith(".jar") &&
    t.split("\\.").forall(seg => seg.nonEmpty && seg.head.isLetter)
  }
}

def classify(cmdline: String, debug: Debug): String = {
  classifications.find(c => cmdline.contains(c.pattern))
    .map(_.name)
    .orElse(extractMainClass(cmdline))    // Phase 2: FQN fallback
    .getOrElse("Scala/JVM")
}
```

**Fallback chain**: known patterns → `extractMainClass()` → "Scala/JVM"

**False positive safety**: The validator rejects classpath entries (`.jar` suffix, `/` path separators) and version-like strings (segments starting with digits). Only fully-qualified class names where every dot-separated segment starts with a letter are accepted (e.g. `com.example.myapp.Main`, `mill.daemon.MillDaemonMain`).
