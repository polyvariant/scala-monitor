# scala-monitor

CLI utility that scans `/proc` for running Scala/JVM-related processes (sbt, bloop, metals, scalac, scala-cli, mill, coursier, scalafmt, scalafix, etc.) and displays a formatted table with PID, type, memory usage (RSS/VSZ/SWAP), thread count, mem%, and project path.

## Build

```sh
scala-cli --power package . -o scala-monitor
```

Produces a `scala-monitor` native binary.

## Run

```sh
./scala-monitor
```

## Help

```
$ ./scala-monitor --help
run
  -f --filter <str>  Filter processes by key=value (repeatable). Keys: type, project. Use * as
                     wildcard for contains matching, case insensitive
  -o --output <str>  Output format: 'full' (table) or 'pid' (just PIDs)
```

## Usage

```sh
./scala-monitor                          # full table (default)
./scala-monitor -o pid                   # only print PIDs
./scala-monitor --filter type=bloop      # filter by process type
./scala-monitor -f type=sbt -f project=*cats*  # multiple filters
./scala-monitor -f type=metals -o pid    # filter Metals processes, output PIDs only
./scala-monitor -f project=~/.local*     # filter by project path
```

### Options

| Flag | Long | Default | Description |
|------|------|---------|-------------|
| `-o` | `--output` | `full` | Output format: `full` (table) or `pid` (PIDs only) |
| `-f` | `--filter` | — | Filter by `key=value`. Keys: `type`, `project`. Use `*` for wildcard (case insensitive). Repeatable. |

## Example output

```
$ ./scala-monitor
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  SCALA PROCESS MONITOR
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  PID      TYPE                RSS          VSZ       SWAP   MEM%   THR  PROJECT                                           
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  2513087  sbt              2.3 GB      23.5 GB       0 kB   7.3%    55  ~/Code/personal/smithy4s                          
  2503995  Metals           698 MB     158.3 GB       0 kB   2.2%    58  ~/Code/personal/scala-monitor                     
  2647652  Bloop            157 MB     384.1 GB       0 kB   0.5%    35  ~/.local/share/scalacli/bloop                     
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  TOTAL: 3 processes, 3.1 GB RSS, 565.9 GB VSZ
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
```

### With flags

```
$ ./scala-monitor -o pid
2513087
2503995
2647652
```

```
$ ./scala-monitor -f type=metals
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  SCALA PROCESS MONITOR
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  PID      TYPE                RSS          VSZ       SWAP   MEM%   THR  PROJECT                                           
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  2503995  Metals           699 MB     158.3 GB       0 kB   2.2%    50  ~/Code/personal/scala-monitor                     
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  TOTAL: 1 processes, 699 MB RSS, 158.3 GB VSZ
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

```

```
$ ./scala-monitor -f type=metals -o pid
2503995
```

## Requirements

- [Scala CLI](https://scala-cli.virtuslab.org/) with Scala Native support
