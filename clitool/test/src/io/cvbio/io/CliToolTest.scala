package io.cvbio.io

import io.cvbio.io.CliTool.ToolException
import io.cvbio.io.testing.UnitSpec
import org.scalatest.{Ignore, Tag}

import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.annotation.nowarn
import scala.collection.mutable.ListBuffer
import scala.sys.process._
import scala.util.{Failure, Success, Try}

/** Unit tests for [[CliTool]]. */
class CliToolTest extends UnitSpec {

  /** Get the path to the named executable. */
  private  def getExecutablePath(executable: String): Path = try {
    val path = Paths.get(s"which $executable".!!.stripLineEnd)
    if (!Files.isExecutable(path)) throw new FileNotFoundException(f"$executable file is not executable: $path")
    path
  } catch {
    case _: RuntimeException =>
      throw new RuntimeException(
        s"Could not find $executable executable on the PATH. Please make sure it is installed and executable."
      )
  }

  "CliToolTest.execCommand" should "execute a command and return stdout and stderr successfully" in {
    val stdout = ListBuffer[String]()
    val stderr = ListBuffer[String]()

    CliTool.execCommand(
      Seq("echo", "hi"), stdoutRedirect = stdout.append, stderrRedirect = stderr.append
    )

    stdout.mkString shouldBe "hi"
    stderr.mkString shouldBe empty
  }

  it should "throw a ToolException with correct exit code when executing an invalid command and return stderr" in {
    val stdout = ListBuffer[String]()
    val stderr = ListBuffer[String]()
    val invalidCommand = Seq("cut", "-invalidArgs")

    intercept[ToolException]{
      CliTool.execCommand(
        invalidCommand,
        stdoutRedirect = s => stdout synchronized { stdout.append(s) }: @nowarn("msg=discarded non-Unit value"),
        stderrRedirect = s => stderr synchronized { stderr.append(s) }: @nowarn("msg=discarded non-Unit value"),
      )
    }.getMessage should include("Command failed with exit code 1: " + invalidCommand.mkString(" "))

    stdout.mkString shouldBe empty
    stderr.mkString should include regex "(invalid|illegal) option"
  }

  it should "fail to execute an invalid command" in {
    val invalidCommand = Seq("cut", "-invalidArgs")
    val attempt        = Try(CliTool.execCommand(invalidCommand))
    attempt.failure.exception.getMessage should include ("invalid")
  }

  it should "execute a java command successfully and return stdout and stderr " in {
    val streams     = new ListBuffer[String]()
    val javaCommand = Seq("java", "-version")

    CliTool.execCommand(
      javaCommand,
      stdoutRedirect = s => streams synchronized { streams.append(s) }: @nowarn("msg=discarded non-Unit value"),
      stderrRedirect = s => streams synchronized { streams.append(s) }: @nowarn("msg=discarded non-Unit value"),
    )

    streams.mkString should include ("version")
  }

  it should "emit a status update on the logger if provided" in {
    val command = Seq("echo", "hi")
    CliTool.execCommand(command, logger = Some(logger))
    logs.mkString should include (s"Executing command: ${command.mkString(" ")}")
  }

  it should "emit no logging when no logger is provided" in {
    val command = Seq("echo", "hi")
    CliTool.execCommand(command, logger = None)
    logs.mkString shouldBe empty
  }

  "CliTool.ToolException" should "wrap the exit code and command in the exception message" in {
    val invalidCommand = Seq("invalidCommand")
    val code           = 2
    ToolException(code, invalidCommand).getMessage shouldBe "Command failed with exit code 2: " + invalidCommand.mkString("")
  }

  object WhenRscriptAvailable extends Tag(if (Try(getExecutablePath(Rscript.executable)).isSuccess) "" else classOf[Ignore].getName)

  Try(getExecutablePath(Rscript.executable)) match {
    case Success(_) =>
      "CliTool.Rscript.execIfAvailable" should "throw a ToolException when running invalid R script if Rscript is available" in {
        val scriptResource = "io/cvbio/io/CliToolFailureTest.R"

        val stdout = ListBuffer[String]()
        val stderr = ListBuffer[String]()

        intercept[ToolException] {
          Rscript.execScript(
            scriptResource = scriptResource,
            args           = Seq.empty,
            logger         = None,
            stdoutRedirect = stdout.append,
            stderrRedirect = stderr.append,
          )
        }.getMessage should include("Command failed with exit code 1: Rscript" )
      }

      it should "emit status update on the logger if a logger is provided " in {
        val scriptResource = "io/cvbio/io/CliToolTest.R"

        Rscript.execScript(
          scriptResource = scriptResource,
          args           = Seq.empty,
          logger         = Some(logger),
          stdoutRedirect = logger.info,
          stderrRedirect = logger.error,
        )
        logs.mkString should include ("Executing script:")
        logs.mkString should include ("Executing command:")
        logs.mkString should include ("Loading required package: stats4")
      }

      it should "emit no logging when no logger is provided " in {
        val scriptResource = "io/cvbio/io/CliToolTest.R"

        Rscript.execScript(
          scriptResource = scriptResource,
          args           = Seq.empty,
          logger         = None,
          stdoutRedirect = _ => (),
          stderrRedirect = _ => (),
        )
        logs.mkString shouldBe empty
      }

      "CliTool.Rscript.ScriptRunner" should "execute a script from resource and emits status update to logger correctly if the " +
        "executable is available and logger is provided" in {
        Rscript.execScript(
          scriptResource = "io/cvbio/io/CliToolTest.R",
          args           = Seq.empty,
          logger         = Some(logger),
          stdoutRedirect = logger.info,
          stderrRedirect = logger.info,
        )

        logs.mkString should include ("Loading required package")
      }

      it should "execute a R script from a given path if the Rscript is available" in {
        val tempFile = Files.createTempFile("CliToolTest.", ".R")

        Files.write(tempFile, "stopifnot(require(\"stats4\"))".getBytes(StandardCharsets.UTF_8))
        tempFile.toFile.deleteOnExit()

        noException should be thrownBy {
          Rscript.execScript(
            scriptPath     = tempFile,
            args           = Seq.empty,
            logger         = None,
            stdoutRedirect = _ => (),
            stderrRedirect = _ => (),
            environment    = Map.empty,
          )
        }
      }

      it should "execute a script from script resource and correctly return stdout and stderr if the executable is available" in {
        val stdout = ListBuffer[String]()
        val stderr = ListBuffer[String]()

        Rscript.execScript(
          scriptResource = "io/cvbio/io/CliToolTest.R",
          args           = Seq.empty,
          logger         = None,
          stdoutRedirect = stdout.append,
          stderrRedirect = stderr.append,
        )
        stdout.mkString shouldBe empty
        stderr.mkString should include ("Loading required package")
      }

      "CliTool.Rscript.Modular" should "test that generic builtins packages are available in R if Rscript is available" in {
        Rscript.moduleAvailable(Seq("stats")) shouldBe true
        Rscript.moduleAvailable(Seq("stats", "stats4")) shouldBe true
      }

      it should "allow for repeated query of the same module and then let us clear the internal cache" in {
        Rscript.moduleAvailable("stats") shouldBe true
        Rscript.moduleAvailable("stats") shouldBe true
        noException shouldBe thrownBy { Rscript.clearModuleAvailableCache() }
      }
    case Failure(_) =>
      "CliTool.Rscript" should "be tested but `Rscript` was not found on the system PATH!" taggedAs WhenRscriptAvailable in {}
  }

  object WhenPythonAvailable extends Tag(if (Try(getExecutablePath(Python.executable)).isSuccess) "" else classOf[Ignore].getName)

  Try(getExecutablePath(Python.executable)) match {
    case Success(_) =>
      "CliTool.Python.ScriptRunner" should "execute a script from resource and emits status update to logger correctly if the " +
        "executable is available and logger is provided" in {
        noException shouldBe thrownBy{
          Python.execScript(
            scriptResource = "io/cvbio/io/CliToolTest.py",
            args           = Seq.empty,
            logger         = None,
            stdoutRedirect = _ => (),
            stderrRedirect = _ => (),
          )
        }
      }

      it should "throw ToolException and correct exit code if trying to run a script from a given path does not exist" in {
        intercept[ToolException]{
          val path = Paths.get("nowhere.py").toAbsolutePath.normalize
          Python.execScript(
            scriptPath     = path,
            args           = Seq.empty,
            logger         = None,
            stdoutRedirect = _ => (),
            stderrRedirect = _ => (),
            environment    = Map.empty,
          )
        }.getMessage should include("Command failed with exit code 2: python")
      }

      it should "throw ToolException and correct exit code when running an script with invalid command" in {
        val stdout = ListBuffer[String]()
        val stderr = ListBuffer[String]()

        intercept[ToolException]{
          Python.execScript(
            scriptResource = "io/cvbio/io/CliToolFailureTest.py",
            args           = Seq.empty,
            logger         = None,
            stdoutRedirect = stdout.append,
            stderrRedirect = stderr.append,
          )
        }.getMessage should include("Command failed with exit code 1: python")

        // Check stderr is written correctly
        stdout.mkString("") shouldBe empty
        stderr.mkString("") should include ("No module named")
      }

      "CliTool.Python.Modular" should "test that generic builtins are available in Python if Python is available" in {
        Python.moduleAvailable(Seq("sys")) shouldBe true
        Python.moduleAvailable(Seq("sys", "os")) shouldBe true
      }

      "CliTool.Python.Versioned" should "emit the current version of Python if Python is available"  in {
        Python.version should include ("Python")

        if (Python3.available){
          Python3.version should include ("Python")
        }

        if (Python2.available){
          Python2.version should include ("Python")
        }
      }

      it should "use its version for availability arguments" in {
        object TestPython extends Python with VersionOnStdOut { override lazy val version: String = "--version" }
        TestPython.argsToTestAvailability should contain theSameElementsInOrderAs Seq("--version")
      }
    case Failure(_) =>
      "CliTool.Python" should "be tested but `Python` was not found on the system PATH!" taggedAs WhenPythonAvailable in {}
  }
}
