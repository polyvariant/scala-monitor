package org.polyvariant

trait SnapshotTest {
  self: munit.FunSuite =>

  def assertSnapshot(obtained: String, expected: String)(implicit loc: munit.Location): Unit =
    assertEquals(obtained.stripMargin.trim, expected.stripMargin.trim)
}
