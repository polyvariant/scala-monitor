# scala-manager

CLI utility that scans `/proc` for running Scala/JVM-related processes (sbt, bloop, metals, scalac, scala-cli, mill, coursier, scalafmt, scalafix, etc.) and displays a formatted table with PID, type, memory usage (RSS/VSZ/SWAP), thread count, mem%, and project path.

## Build

```sh
scala-cli --power package .
```

Produces a `scala-manager` native binary.

## Run

```sh
./scala-manager
```

## Requirements

- [Scala CLI](https://scala-cli.virtuslab.org/) with Scala Native support
