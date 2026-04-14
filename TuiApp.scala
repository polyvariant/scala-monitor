package org.polyvariant

import layoutz._
import layoutz.Color

enum SortColumn { case Pid, Kind, Ram, MemPercent, Project }

enum ConfirmationKind { case Sigkill, None }

case class TuiState(
  processes: List[ScalaProcess],
  selectedIndex: Int,
  sortColumn: SortColumn,
  sortDescending: Boolean,
  statusMessage: Option[String],
  statusMessageExpiresAt: Long,
  confirmation: ConfirmationKind,
  confirmTargetPid: Option[Int],
  tickFrame: Int
)

sealed trait TuiMsg

case object RefreshProcesses extends TuiMsg
case class ProcessesLoaded(procs: List[ScalaProcess]) extends TuiMsg
case object MoveUp extends TuiMsg
case object MoveDown extends TuiMsg
case object SortCycle extends TuiMsg
case object RequestSigterm extends TuiMsg
case object RequestSigkill extends TuiMsg
case object ConfirmKill extends TuiMsg
case object CancelConfirmation extends TuiMsg
case object RequestThreadDump extends TuiMsg
case object RequestHeapDump extends TuiMsg
case class ActionCompleted(msg: String) extends TuiMsg
case class ActionFailed(err: String) extends TuiMsg
case object ClearStatus extends TuiMsg
case object TickFrame extends TuiMsg
case object Quit extends TuiMsg

object TuiApp {

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

  def run(debug: Boolean): Unit = {
    val app = new TuiApp(debug)
    app.run(
      tickIntervalMs = 100,
      renderIntervalMs = 50,
      clearOnStart = true,
      clearOnExit = true,
      showQuitMessage = false,
      quitKey = Key.Ctrl('C')
    )
  }
}

class TuiApp(debug: Boolean) extends LayoutzApp[TuiState, TuiMsg] {

  def init: (TuiState, Cmd[TuiMsg]) = {
    val procs = ScalaMonitor.discover(debug)
    val sorted = TuiApp.sort(procs, SortColumn.Ram, ascending = false)
    val state = TuiState(
      processes = sorted,
      selectedIndex = 0,
      sortColumn = SortColumn.Ram,
      sortDescending = true,
      statusMessage = None,
      statusMessageExpiresAt = 0L,
      confirmation = ConfirmationKind.None,
      confirmTargetPid = None,
      tickFrame = 0
    )
    (state, Cmd.none)
  }

  def update(msg: TuiMsg, state: TuiState): (TuiState, Cmd[TuiMsg]) = msg match {
    case RefreshProcesses =>
      (state, Cmd.task(ScalaMonitor.discover(debug)) {
        case Right(procs) => ProcessesLoaded(procs)
        case Left(err)    => ActionFailed(err)
      })

    case ProcessesLoaded(procs) =>
      val sorted = TuiApp.sort(procs, state.sortColumn, !state.sortDescending)
      val maxIdx = math.max(0, sorted.size - 1)
      val clamped = math.min(state.selectedIndex, maxIdx)
      state.copy(processes = sorted, selectedIndex = clamped)

    case MoveUp =>
      state.copy(selectedIndex = math.max(0, state.selectedIndex - 1))

    case MoveDown =>
      val maxIdx = math.max(0, state.processes.size - 1)
      state.copy(selectedIndex = math.min(maxIdx, state.selectedIndex + 1))

    case SortCycle =>
      val columns = List(SortColumn.Pid, SortColumn.Kind, SortColumn.Ram, SortColumn.MemPercent, SortColumn.Project)
      val currentIdx = columns.indexOf(state.sortColumn)
      val nextIdx = (currentIdx + 1) % columns.size
      val nextCol = columns(nextIdx)
      val sorted = TuiApp.sort(state.processes, nextCol, ascending = false)
      state.copy(processes = sorted, sortColumn = nextCol, sortDescending = true)

    case RequestSigterm =>
      state.processes.lift(state.selectedIndex) match {
        case Some(proc) =>
          val result = ProcessActions.sendSigterm(proc.pid)
          result match {
            case Right(msg) => state.copy(statusMessage = Some(msg), statusMessageExpiresAt = System.currentTimeMillis() + 3000)
            case Left(err)  => state.copy(statusMessage = Some("Error: " + err), statusMessageExpiresAt = System.currentTimeMillis() + 5000)
          }
        case None => state
      }

    case RequestSigkill =>
      state.processes.lift(state.selectedIndex) match {
        case Some(proc) =>
          state.copy(confirmation = ConfirmationKind.Sigkill, confirmTargetPid = Some(proc.pid))
        case None => state
      }

    case ConfirmKill =>
      state.confirmTargetPid match {
        case Some(pid) =>
          val result = ProcessActions.sendSigkill(pid)
          result match {
            case Right(msg) =>
              state.copy(
                statusMessage = Some(msg),
                statusMessageExpiresAt = System.currentTimeMillis() + 3000,
                confirmation = ConfirmationKind.None,
                confirmTargetPid = None
              )
            case Left(err) =>
              state.copy(
                statusMessage = Some("Error: " + err),
                statusMessageExpiresAt = System.currentTimeMillis() + 5000,
                confirmation = ConfirmationKind.None,
                confirmTargetPid = None
              )
          }
        case None => state.copy(confirmation = ConfirmationKind.None, confirmTargetPid = None)
      }

    case CancelConfirmation =>
      state.copy(confirmation = ConfirmationKind.None, confirmTargetPid = None)

    case RequestThreadDump =>
      state.processes.lift(state.selectedIndex) match {
        case Some(proc) =>
          val result = ProcessActions.threadDump(proc.pid)
          result match {
            case Right(msg) => state.copy(statusMessage = Some(msg), statusMessageExpiresAt = System.currentTimeMillis() + 3000)
            case Left(err)  => state.copy(statusMessage = Some("Error: " + err), statusMessageExpiresAt = System.currentTimeMillis() + 5000)
          }
        case None => state
      }

    case RequestHeapDump =>
      state.processes.lift(state.selectedIndex) match {
        case Some(proc) =>
          val result = ProcessActions.heapDump(proc.pid)
          result match {
            case Right(msg) => state.copy(statusMessage = Some(msg), statusMessageExpiresAt = System.currentTimeMillis() + 3000)
            case Left(err)  => state.copy(statusMessage = Some("Error: " + err), statusMessageExpiresAt = System.currentTimeMillis() + 5000)
          }
        case None => state
      }

    case ActionCompleted(msg) =>
      state.copy(statusMessage = Some(msg), statusMessageExpiresAt = System.currentTimeMillis() + 3000)

    case ActionFailed(err) =>
      state.copy(statusMessage = Some("Error: " + err), statusMessageExpiresAt = System.currentTimeMillis() + 5000)

    case ClearStatus =>
      state.copy(statusMessage = None)

    case TickFrame =>
      val now = System.currentTimeMillis()
      val cleared = if (state.statusMessageExpiresAt > 0 && now >= state.statusMessageExpiresAt) {
        state.copy(statusMessage = None, statusMessageExpiresAt = 0L)
      } else {
        state
      }
      cleared.copy(tickFrame = cleared.tickFrame + 1)

    case Quit =>
      (state, Cmd.exit)
  }

  def subscriptions(state: TuiState): Sub[TuiMsg] = {
    val confirming = state.confirmation != ConfirmationKind.None
    Sub.batch(
      Sub.time.everyMs(1000L, RefreshProcesses),
      Sub.time.everyMs(100L, TickFrame),
      Sub.onKeyPress {
        case Key.Char('q')              => Some(Quit)
        case Key.Up | Key.Char('k')      => Some(MoveUp)
        case Key.Down | Key.Char('j')    => Some(MoveDown)
        case Key.Char('d')               => Some(RequestSigterm)
        case Key.Ctrl('K')               => Some(RequestSigkill)
        case Key.Char('t')               => Some(RequestThreadDump)
        case Key.Char('h')               => Some(RequestHeapDump)
        case Key.Char('F')               => Some(SortCycle)
        case Key.Enter if confirming     => Some(ConfirmKill)
        case Key.Escape if confirming    => Some(CancelConfirmation)
        case _                           => None
      }
    )
  }

  def view(state: TuiState): Element = {
    val totalRam = state.processes.map(_.ramKb).sum
    val processWord = if (state.processes.size == 1) "process" else "processes"
    val sortLabel = state.sortColumn match {
      case SortColumn.Pid       => "PID"
      case SortColumn.Kind      => "TYPE"
      case SortColumn.Ram       => "RAM"
      case SortColumn.MemPercent => "MEM%"
      case SortColumn.Project   => "PROJ"
    }
    val sortArrow = if (state.sortDescending) "v" else "^"

    val headerText = s"SCALA PROCESS MONITOR -- ${state.processes.size} $processWord -- ${ScalaMonitor.formatMemory(totalRam)} RAM"
    val header = banner(headerText).border(Border.Round)

    val infoBar = row(
      statusCard("Refresh", "1s"),
      statusCard("Sort", s"$sortLabel $sortArrow")
    )

    val tableHeaders = List("PID", "TYPE", "RSS", "SWAP", "MEM%", "THR", "PROJECT")
    val tableRows = state.processes.zipWithIndex.map { case (p, idx) =>
      val swapStr = p.swapKb.map(ScalaMonitor.formatMemory).getOrElse("n/a")
      val marker = if (idx == state.selectedIndex) ">" else " "
      List(
        s"$marker${p.pid}",
        p.kind,
        ScalaMonitor.formatMemory(p.ramKb),
        swapStr,
        f"${p.memPercent}%.1f%%",
        p.threads.toString,
        p.projectPath
      )
    }

    val tableHeadersE: Seq[Element] = tableHeaders.map(h => h: Element)
    val tableRowsE: Seq[Seq[Element]] = tableRows.map(row => row.map(s => s: Element))

    val tableElement = if (tableRows.nonEmpty) {
      table(tableHeadersE, tableRowsE).border(Border.Round)
    } else {
      val emptyRow: Seq[Element] = Seq("No Scala processes found", "", "", "", "", "", "").map(s => s: Element)
      table(tableHeadersE, Seq(emptyRow)).border(Border.Round)
    }

    val footerText = "Arrows navigate | d SIGTERM | Ctrl+K KILL | t threads | h heap | F sort | q quit"
    val footer = (footerText: Element).color(Color.BrightBlack)

    val statusFlash = state.statusMessage.map { msg =>
      (s" ! $msg": Element).color(Color.Yellow)
    }

    val confirmationOverlay = state.confirmation match {
      case ConfirmationKind.Sigkill =>
        state.confirmTargetPid.map { pid =>
          val confirmText = s"!! Force kill PID $pid?  Enter=confirm  Esc=cancel"
          box(confirmText)().border(Border.Round).color(Color.Red)
        }
      case ConfirmationKind.None => None
    }

    if (confirmationOverlay.isDefined) {
      layout(header, infoBar, tableElement, confirmationOverlay.get)
    } else {
      val flashElement = statusFlash match {
        case Some(flash) => layout(footer, flash)
        case None        => layout(footer)
      }
      layout(header, infoBar, tableElement, flashElement)
    }
  }
}
