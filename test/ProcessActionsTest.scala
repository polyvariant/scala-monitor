package org.polyvariant

class ProcessActionsTest extends munit.FunSuite {

  test("threadDump returns success message") {
    val result = ProcessActions.threadDump(12345)
    assertEquals(result, Right("Thread dump requested for PID 12345 -> /tmp/threads-12345.hprof"))
  }

  test("heapDump returns success message") {
    val result = ProcessActions.heapDump(12345)
    assertEquals(result, Right("Heap dump requested for PID 12345 -> /tmp/heap-12345.hprof"))
  }
}
