package org.polyvariant

import scala.scalanative.unsafe._

@extern
object libproc {
  def proc_listallpids(buffer: Ptr[Int], buffersize: Int): Int = extern
  def proc_pidinfo(pid: Int, flavor: Int, arg: Long, buffer: Ptr[Byte], buffersize: Int): Int = extern
  def proc_pidpath(pid: Int, buffer: Ptr[Byte], buffersize: Int): Int = extern
}

@extern
object macsysctl {
  def sysctl(name: Ptr[Int], namelen: Int, oldp: Ptr[Byte], oldlenp: Ptr[Long], newp: Ptr[Byte], newlen: Long): Int = extern
  def sysctlbyname(name: CString, oldp: Ptr[Byte], oldlenp: Ptr[Long], newp: Ptr[Byte], newlen: Long): Int = extern
}
