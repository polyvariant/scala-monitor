package org.polyvariant

import layoutz._
import layoutz.Color
import layoutz.Key.Ctrl
import layoutz.Key.Unknown
import layoutz.Key.Enter
import layoutz.Key.Escape
import layoutz.Key.Tab
import layoutz.Key.Backspace
import layoutz.Key.Delete
import layoutz.Key.Up
import layoutz.Key.Down
import layoutz.Key.Home
import layoutz.Key.End
import layoutz.Key.PageUp
import layoutz.Key.PageDown

enum SortColumn { case Pid, Kind, Ram, MemPercent, Project }

enum ConfirmationKind { case Sigkill, None }

case class TuiState(
  processes: List[ScalaProcess],
  selectedIndex: Int,
  sortColumn: SortColumn,
  sortDirection: SortDirection,
  statusMessage: Option[String],
  statusMessageExpiresAt: Long,
  confirmation: ConfirmationKind,
  confirmTargetPid: Option[Int],
  tickFrame: Int,
  showHelp: Boolean = false,
  termWidth: Int = 80
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
case object ToggleHelp extends TuiMsg
case object JumpToFirst extends TuiMsg
case object JumpToLast extends TuiMsg

enum SortDirection {
  case Ascending
  case Descending

  def swap: SortDirection =
    this match {
      case Ascending => Descending
      case Descending => Ascending
    }
}

object TuiApp {

  def sort(procs: List[ScalaProcess], column: SortColumn, sorting: SortDirection): List[ScalaProcess] = {
    val ordering = column match {
      case SortColumn.Pid       => Ordering.by[ScalaProcess, Int](_.pid)
      case SortColumn.Kind      => Ordering.by[ScalaProcess, String](_.kind)
      case SortColumn.Ram       => Ordering.by[ScalaProcess, Long](_.ramKb)
      case SortColumn.MemPercent => Ordering.by[ScalaProcess, Double](_.memPercent)
      case SortColumn.Project   => Ordering.by[ScalaProcess, String](_.projectPath)
    }
    val sorted = procs.sorted(using ordering)
    if (sorting == SortDirection.Ascending) sorted else sorted.reverse
  }

  def run(debug: Boolean): Unit = {
    val processActions = ProcessActionsLive(scala.scalanative.posix.signal)
    val app = new TuiApp(debug, processActions)
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

class TuiApp(debug: Boolean, processActions: ProcessActions) extends LayoutzApp[TuiState, TuiMsg] {

  def init: (TuiState, Cmd[TuiMsg]) = {
    val procs = ScalaMonitor.discover(debug)
    val sorted = TuiApp.sort(procs, SortColumn.Ram, SortDirection.Descending)
    val tw = math.min(210, SttyTerminal.create().map(_.terminalWidth()).getOrElse(80))
    val state = TuiState(
      processes = sorted,
      selectedIndex = 0,
      sortColumn = SortColumn.Ram,
      sortDirection = SortDirection.Descending,
      statusMessage = None,
      statusMessageExpiresAt = 0L,
      confirmation = ConfirmationKind.None,
      confirmTargetPid = None,
      tickFrame = 0,
      showHelp = false,
      termWidth = tw
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
      val sorted = TuiApp.sort(procs, state.sortColumn, state.sortDirection)
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
      val sorted = TuiApp.sort(state.processes, nextCol, SortDirection.Descending)
      state.copy(processes = sorted, sortColumn = nextCol, sortDirection = SortDirection.Descending)

    case RequestSigterm =>
      state.processes.lift(state.selectedIndex) match {
        case Some(proc) =>
          val result = processActions.sendSigterm(proc.pid)
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
          val result = processActions.sendSigkill(pid)
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
          val result = processActions.threadDump(proc.pid)
          result match {
            case Right(msg) => state.copy(statusMessage = Some(msg), statusMessageExpiresAt = System.currentTimeMillis() + 3000)
            case Left(err)  => state.copy(statusMessage = Some("Error: " + err), statusMessageExpiresAt = System.currentTimeMillis() + 5000)
          }
        case None => state
      }

    case RequestHeapDump =>
      state.processes.lift(state.selectedIndex) match {
        case Some(proc) =>
          val result = processActions.heapDump(proc.pid)
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

    case ToggleHelp =>
      state.copy(showHelp = !state.showHelp)

    case JumpToFirst =>
      state.copy(selectedIndex = 0)

    case JumpToLast =>
      val maxIdx = math.max(0, state.processes.size - 1)
      state.copy(selectedIndex = maxIdx)

    case Quit =>
      (state, Cmd.exit)
  }

  def subscriptions(state: TuiState): Sub[TuiMsg] = {
    val confirming = state.confirmation != ConfirmationKind.None
    Sub.batch(
      Sub.time.everyMs(1000L, RefreshProcesses),
      Sub.time.everyMs(100L, TickFrame),
      Sub.onKeyPress { key =>
        if(confirming) key match {
          case Key.Enter if confirming     => Some(ConfirmKill)
          case Key.Escape if confirming    => Some(CancelConfirmation)
          case _ => None
        } else key match {
          case Key.Char('q')               => Some(Quit)
          case Key.Up                      => Some(MoveUp)
          case Key.Down                    => Some(MoveDown)
          case Key.Char('k')               => Some(MoveUp)
          case Key.Char('j')               => Some(MoveDown)
          case Key.Char('d')               => Some(RequestSigterm)
          case Key.Char('x')               => Some(RequestSigkill)
          case Key.Char('t')               => Some(RequestThreadDump)
          case Key.Char('h')               => Some(RequestHeapDump)
          case Key.Char('f')               => Some(SortCycle)
          case Key.Char('?')               => Some(ToggleHelp)
          case Key.Char('g')               => Some(JumpToFirst)
          case Key.Char('G')               => Some(JumpToLast)
          case _                           => None
        }
      }
    )
  }

  def view(state: TuiState): Element = {
    val totalRam = state.processes.map(_.ramKb).sum
    val processWord = if (state.processes.size == 1) "proc" else "procs"
    val sortLabel = state.sortColumn match {
      case SortColumn.Pid       => "PID"
      case SortColumn.Kind      => "TYPE"
      case SortColumn.Ram       => "RAM"
      case SortColumn.MemPercent => "MEM%"
      case SortColumn.Project   => "PROJ"
    }
    val sortArrow = if (state.sortDirection == SortDirection.Descending) "\u25BE" else "\u25B4"
    val titleText = s" SCALA MONITOR \u2500\u2500 ${state.processes.size} $processWord \u2500\u2500 ${ScalaMonitor.formatMemory(totalRam)} \u2500\u2500 $sortLabel $sortArrow "
    val brandText = "https://polyvariant.org"
    val brandW = brandText.length

    val allProcs = state.processes
    val pidW = math.max(3, allProcs.map(_.pid.toString.length).maxOption.getOrElse(0))
    val kindW = math.max(16, allProcs.map(_.kind.length).maxOption.getOrElse(0))
    val rssW = math.max(3, allProcs.map(p => ScalaMonitor.formatMemory(p.ramKb).length).maxOption.getOrElse(0))
    val swapW = math.max(4, allProcs.map(p => p.swapKb.map(ScalaMonitor.formatMemory).getOrElse("n/a").length).maxOption.getOrElse(0))
    val memW = math.max(4, allProcs.map(p => f"${p.memPercent}%.1f%%".length).maxOption.getOrElse(0))
    val thrW = math.max(3, allProcs.map(_.threads.toString.length).maxOption.getOrElse(0))
    val nonProjectContentW = pidW + kindW + rssW + swapW + memW + thrW
    // layoutz table: 1 space padding each side per column, │ separators
    // 7 columns: 7*2 padding + 8 │ chars (left/right borders + inter-column)
    val tableOverhead = 7 * 2 + 8
    val availWidth = state.termWidth - 1 // reserve 1 column
    val projectMaxWidth = math.max(7, availWidth - nonProjectContentW - tableOverhead)
    val tableWidth = nonProjectContentW + projectMaxWidth + tableOverhead
    val titleAvail = math.max(1, tableWidth - brandW)
    val titleRealLen = realLength(titleText)
    val displayTitle = if (titleRealLen > titleAvail) titleText.take(titleAvail - 1) + "\u2026"
    else titleText + (" " * (titleAvail - titleRealLen))

    val titleRow = rowTight(
      (displayTitle: Element).color(Color.Cyan).style(Style.Bold),
      (brandText: Element).color(Color.BrightBlack)
    )

    def padRight(s: String, w: Int): String =
      if (s.length >= w) s else s + (" " * (w - s.length))

    val tableHeadersE: Seq[Element] = List(
      padRight("PID", pidW),
      padRight("TYPE", kindW),
      padRight("RSS", rssW),
      padRight("SWAP", swapW),
      padRight("MEM%", memW),
      padRight("THR", thrW),
      padRight("PROJECT", projectMaxWidth)
    ).map(h => (h: Element).color(Color.Cyan).style(Style.Bold))

    val tableRowsE: Seq[Seq[Element]] = state.processes.zipWithIndex.map { case (p, idx) =>
      val isSel = idx == state.selectedIndex
      val bg: Element => Element = if (isSel) e => e.bg(Color.True(30, 60, 90)).style(Style.Bold) else identity[Element]
      val bgWhite: Element => Element = if (isSel) e => e.bg(Color.True(30, 60, 90)).color(Color.White).style(Style.Bold) else identity[Element]
      val rssGb = p.ramKb.toDouble / (1024.0 * 1024.0)
      val rssColor: Color = if (rssGb >= 6.0) Color.Red else if (rssGb >= 2.0) Color.Yellow else Color.Green
      val memColor: Color = if (p.memPercent >= 15.0) Color.Red else if (p.memPercent >= 5.0) Color.Yellow else Color.Green
      val kc: Color = p.kind.toLowerCase match {
        case "sbt"       => Color.Magenta
        case "metals"    => Color.Blue
        case "bloop"     => Color.Green
        case "scala-cli" => Color.Cyan
        case "scalac"    => Color.Yellow
        case _           => Color.White
      }
      val swapStr = p.swapKb.map(ScalaMonitor.formatMemory).getOrElse("n/a")
      val displayPath = if (p.projectPath.length > projectMaxWidth)
        p.projectPath.take(projectMaxWidth - 3) + "..."
      else
        p.projectPath + (" " * (projectMaxWidth - p.projectPath.length))
      Seq(
        bgWhite(padRight(p.pid.toString, pidW)),
        bg(padRight(p.kind, kindW)),
        bg((padRight(ScalaMonitor.formatMemory(p.ramKb), rssW): Element).color(rssColor)),
        bg(padRight(swapStr, swapW)),
        bg((padRight(f"${p.memPercent}%.1f%%", memW): Element).color(memColor)),
        bgWhite(padRight(p.threads.toString, thrW)),
        bg(displayPath)
      )
    }

    val tableElement = if (tableRowsE.nonEmpty) {
      layout(titleRow, table(tableHeadersE, tableRowsE).border(Border.Round))
    } else {
      val emptyTitleText = s" SCALA MONITOR \u2500\u2500 0 $processWord \u2500\u2500 0 kB "
      val emptyAvail = math.max(1, availWidth - brandW)
      val emptyTitleRealLen = realLength(emptyTitleText)
      val emptyDisplayTitle = if (emptyTitleRealLen > emptyAvail) emptyTitleText.take(emptyAvail - 1) + "\u2026"
      else emptyTitleText + (" " * (emptyAvail - emptyTitleRealLen))
      val emptyTitleRow = rowTight(
        (emptyDisplayTitle: Element).color(Color.Cyan).style(Style.Bold),
        (brandText: Element).color(Color.BrightBlack)
      )
      val emptyMsg = layout(
        "  No Scala processes found",
        "  Launch sbt, scala-cli, metals, or bloop to see them here"
      )
      layout(emptyTitleRow, box("")(emptyMsg).border(Border.Round))
    }

    val footerText = " \u2191\u2193 kj nav  d term  x kill  t threads  h heap  F sort  ? help  q quit"
    val footer = (footerText: Element).color(Color.BrightBlack)

    val statusFlash = state.statusMessage.map { msg =>
      if (msg.startsWith("Error:"))
        (s" \u2717 $msg": Element).color(Color.Red)
      else if (msg.contains("dump"))
        (s" \u23F3 $msg": Element).color(Color.Yellow)
      else
        (s" \u2713 $msg": Element).color(Color.Green)
    }

    val confirmationOverlay = state.confirmation match {
      case ConfirmationKind.Sigkill =>
        state.confirmTargetPid.flatMap { pid =>
          state.processes.find(_.pid == pid).map { proc =>
            val line1 = (s"  Kill ${proc.kind} (PID ${proc.pid})?": Element).style(Style.Bold)
            val line2 = ("  Enter confirm   Esc cancel": Element).style(Style.Bold)
            box(s" Kill Process \u2500\u2500 Esc to close ")(line1, line2)
              .border(Border.Round).color(Color.Red)
          }
        }
      case ConfirmationKind.None => None
    }

    val helpContent = layout(
      (" Navigation ": Element).style(Style.Bold),
      "   \u2191\u2193 k / j    Navigate up / down",
      "   g / G       Jump to first / last row",
      (" Actions ": Element).style(Style.Bold),
      "   d           Send SIGTERM",
      "   x           Send SIGKILL (confirm)",
      "   t           Request thread dump (wip)",
      "   h           Request heap dump (wip)",
      (" Sort ": Element).style(Style.Bold),
      "   F           Cycle sort column",
      (" Misc ": Element).style(Style.Bold),
      "   ?           Toggle this help",
      "   q           Quit"
    )
    val helpBox = box("")(helpContent).border(Border.Round)

    if (state.showHelp) {
      layout(helpBox, footer)
    } else if (confirmationOverlay.isDefined) {
      layout(tableElement, confirmationOverlay.get, footer)
    } else {
      statusFlash match {
        case Some(flash) => layout(tableElement, layout(footer, flash))
        case None        => layout(tableElement, footer)
      }
    }
  }
}
