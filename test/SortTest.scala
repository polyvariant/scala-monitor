package org.polyvariant

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
