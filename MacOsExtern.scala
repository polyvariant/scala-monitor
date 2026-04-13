package org.polyvariant

import scala.scalanative.unsafe._

@extern
object libproc {
  def proc_pidinfo(pid: Int, flavor: Int, arg: Long, buffer: Ptr[Byte], buffersize: Int): Int = extern
}

@extern
object popenlib {
  def popen(command: CString, mode: CString): Ptr[Byte] = extern
  def pclose(stream: Ptr[Byte]): CInt = extern
}
