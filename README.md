# clitool

[![Unit Tests](https://github.com/clintval/clitool/actions/workflows/unit-tests.yml/badge.svg?branch=main)](https://github.com/clintval/clitool/actions/workflows/unit-tests.yml?query=branch%3Amain)
[![Java Version](https://img.shields.io/badge/java-11,17,21-c22d40.svg)](https://github.com/AdoptOpenJDK/homebrew-openjdk)
[![Language](https://img.shields.io/badge/language-scala-c22d40.svg)](https://www.scala-lang.org/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/clintval/clitool/blob/master/LICENSE)

A small set of Scala traits to help execute command line tools.

![Cisco and the Grand Canyon](.github/img/cover.jpg)

```scala
val ScriptResource = "io/cvbio/io/CliToolTest.py"

Python3.execScript(
  scriptResource = ScriptResource,
  args           = Seq.empty,
  logger         = Some(logger),
  stdoutRedirect = logger.info,
  stderrRedirect = logger.warning,
)
```

#### If Mill is your build tool

```scala
ivyDeps ++ Agg(ivy"io.cvbio.io::clitool::0.1.0")
```

#### If SBT is your build tool

```scala
libraryDependencies += "io.cvbio.io" %% "clitool" % "0.1.0"
```