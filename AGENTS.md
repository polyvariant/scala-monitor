# AGENTS.md

## Build

```sh
scala-cli --power package . -o scala-monitor -f
```

Produces a native binary. Requires `scala-cli` with Scala Native support.

## Style

- **Braces required**: `project.scala` uses `-no-indent`. All Scala code must use `{}` not significant indentation.
- **No `var`**: all code is functional/immutable. Zero `var` usage enforced.
- **mainargs `Flag` for booleans**: use `mainargs.Flag` (not `Boolean`) for flag-only CLI args like `--debug`. Access via `.value`.
- **mainargs needs explicit `main`**: `@main` does NOT auto-generate `def main(args: Array[String])` — it must be written explicitly and is the sole entry point.

## Architecture

Single-package project (`org.polyvariant`). Platform dispatch at link time via `LinktimeInfo.isMac`/`isLinux`.

- **`ScalaMonitor.scala`** — entry point, CLI parsing, process classification, table rendering
- **`PlatformProbe.scala`** — `ScalaProcess` case class + `PlatformProbe` trait
- **`LinuxProbe.scala`** — reads `/proc/[pid]/{cmdline,status,cwd}` directly
- **`MacOsProbe.scala`** — shells out to `ps -eo pid=,%mem=,rss=,args= -ww`, then FFI for CWD only
- **`MacOsExtern.scala`** — custom FFI bindings (`proc_pidinfo` + `popen`/`pclose`)
- **`Debug.scala`** — conditional stderr logging, passed as constructor param to probes

## macOS gotchas

- `ps` fields differ from Linux: no `nlwp`, no `vsz` (useless for JVM anyway). Use `pid=,%mem=,rss=,args=` with `-ww`.
- `popen`/`pclose` are NOT in Scala Native's `scala.scalanative.libc.stdio` — custom FFI in `popenlib` object.
- `fgets` IS available from `scala.scalanative.libc.stdio`.
- `Ptr[Byte]` used as opaque FILE* for popen/pclose.
- Use `LinktimeInfo.is32BitPlatform` (not `!LinktimeInfo.is64BitPlatform` — that doesn't exist in SN 0.5.10).

## Testing

Integration test in `scripts/integration-test.sh` — expects a pre-built `scala-monitor` binary. Launches real `scala-cli` and `sbt` processes, then asserts detection. CI installs sbt via `cs install sbt --install-dir /usr/local/bin`.

```sh
./scripts/integration-test.sh
```

## CI

Matrix build on `ubuntu-latest` + `macos-latest`. Tags `v*` trigger GitHub Release with two artifacts: `scala-monitor-linux` and `scala-monitor-darwin`.
