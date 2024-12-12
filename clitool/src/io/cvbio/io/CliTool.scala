package io.cvbio.io

import org.slf4j.Logger

import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.parallel.CollectionConverters._
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.Properties.lineSeparator
import scala.util.Try

/** A base trait for a commandline tool that defines values associated with the tool.
  * For example, the name of the executable and if the executable is available to be executed.
  */
trait CliTool {

  /** The name of the executable. */
  val executable: String

  /** The arguments that are used to test if the executable is available. */
  val argsToTestAvailability: Seq[String]

  /** True if the tool is available and false otherwise. */
  lazy val available: Boolean = Try(CliTool.execCommand(Seq(executable) ++ argsToTestAvailability)).isSuccess
}

/** Indicates the executable can run scripts. */
trait ScriptRunner {
  self: CliTool =>

  /** Suffix (file extension) of scripts that can be run by this tool. */
  val scriptSuffix: String

  /** Executes a script from the classpath, raise an exception otherwise.
    *
    * @throws Exception when we are unable to execute to this script on the classpath with the given arguments.
    * @throws ToolException when the exit code from the called process is not zero.
    * @param scriptResource the name of the script resource on the classpath
    * @param args a variable list of arguments to pass to the script
    * @param logger an optional logger to use for emitting status updates
    * @param stdoutRedirect an optional function that will capture or sink away the stdout of the underlying process
    * @param stderrRedirect an optional function that will capture or sink away the stderr of the underlying process
    * @param environment key value pairs to set in the process's environment before execution
    */
  def execScript(
    scriptResource: String,
    args: Seq[String]                = Seq.empty,
    logger: Option[Logger]           = None,
    stdoutRedirect: String => Unit   = _ => (),
    stderrRedirect: String => Unit   = _ => (),
    environment: Map[String, String] = Map.empty,
  ): Unit = {
    execScript(
      CliTool.writeResourceToTempFile(scriptResource),
      args           = args,
      logger         = logger,
      stdoutRedirect = stdoutRedirect,
      stderrRedirect = stderrRedirect,
      environment    = environment,
    )
  }

  /** Executes a script from the filesystem path.
    *
    * @throws Exception when we are unable to execute the script with the given arguments
    * @throws ToolException when the exit code from the called process is not zero
    * @param scriptPath Path to the script to be executed
    * @param args a variable list of arguments to pass to the script
    * @param logger an optional logger to use for emitting status updates
    * @param stdoutRedirect an optional function that will capture or sink away the stdout of the underlying process
    * @param stderrRedirect an optional function that will capture or sink away the stderr of the underlying process
    * @param environment key value pairs to set in the process's environment before execution
    */
  def execScript(
    scriptPath: Path,
    args: Seq[String],
    logger: Option[Logger],
    stdoutRedirect: String => Unit,
    stderrRedirect: String => Unit,
    environment: Map[String, String],
  ): Unit = {

    val basename = scriptPath.getFileName.toString
    val command  = Seq(executable, scriptPath.toAbsolutePath.toString) ++ args

    logger.foreach { log =>
      log.info(s"Executing script: $basename with $executable using the arguments: '${args.mkString(" ")}'")
    }

    try {
      CliTool.execCommand(
        command,
        logger         = logger,
        stdoutRedirect = stdoutRedirect,
        stderrRedirect = stderrRedirect,
        environment    = environment,
      )
    } catch { case exception: Throwable =>
      logger.foreach(_.error(s"Failed to execute $basename using $executable with arguments: '${args.mkString(" ")}'" ))
      throw exception
    }
  }
}

/** Defines values used to get the version of the executable. */
private[io] trait Versioned {
  self: CliTool =>

  /** The default version flag. */
  val versionFlag: String = "--version"

  /** Use the version flag to test the successful install of the executable. */
  lazy val argsToTestAvailability: Seq[String] = Seq(versionFlag) // Must be lazy in case versionFlag is overridden

  /** Version of this executable. */
  val version: String
}

/** Defines values used to get the version of the executable from both stdout and stderr. */
trait VersionOnStream extends Versioned {
  self: CliTool =>

  /** Version of this executable. */
  lazy val version: String = {
    val versionBuffer = ListBuffer[String]()
    def redirect(s: String): Unit = { val _ = versionBuffer.synchronized(versionBuffer.addOne(s)) }
    CliTool.execCommand(Seq(executable, versionFlag), stdoutRedirect = redirect, stderrRedirect = redirect)
    versionBuffer.mkString(lineSeparator)
  }
}

/** Defines values used to get the version from stdout. */
trait VersionOnStdOut extends Versioned {
  self: CliTool =>

  /** Version of this executable. */
  lazy val version: String = {
    val version_buffer = ListBuffer[String]()
    CliTool.execCommand(Seq(executable, versionFlag), stdoutRedirect = version_buffer.addOne)
    version_buffer.mkString(lineSeparator)
  }
}

/** Defines values used to get the version from stderr. */
trait VersionOnStdErr extends Versioned {
  self: CliTool =>

  /** Version of this executable. */
  lazy val version: String = {
    val version_buffer = ListBuffer[String]()
    CliTool.execCommand(Seq(executable, versionFlag), stderrRedirect = version_buffer.addOne)
    version_buffer.mkString(lineSeparator)
  }
}

/** Defines methods used to check if specific modules are installed with the executable . */
trait Modular {
  self: CliTool =>

  /** A cache for remembering which modules are available to speedup successive calls. */
  private lazy val cache: mutable.Map[String, Boolean] = mutable.Map.empty

  /** The command to use to test the existence of a module with the executable. */
  def testModuleCommand(module: String): Seq[String]

  /** Returns true if the tested module exists with the tested executable. */
  def moduleAvailable(module: String): Boolean = {
    this.cache synchronized {
      this.available && cache.getOrElseUpdate(module, Try(CliTool.execCommand(testModuleCommand(module))).isSuccess)
    }
  }

  /** Returns true if all tested modules exist with the tested executable.
    *
    * For example:
    * {{
    * scala> import io.cvbio.io._
    * scala> Rscript.ModuleAvailable(Seq("stats", "stats4"))
    * res1: Boolean = true
    * }}
    */
  def moduleAvailable(modules: Seq[String]): Boolean = {
    modules.toList.par.map(moduleAvailable).forall(_ == true)
  }

  /** Clear the internal cache that remembers which modules are available. */
  def clearModuleAvailableCache(): Unit = cache synchronized { cache.clear() }
}

/** Companion object for [[CliTool]]. */
object CliTool {

  /** The characters that are illegal to have in file paths. */
  private[io] val IllegalPathCharacters: Set[Char] = "[!\"#$%&'()*/:;<=>?@\\^`{|}~] ".toSet

  /** The maximum number of characters allowed in a filename. */
  private[io] val MaxFileNameSize: Int = 254

  /** Exception class that holds onto the exit/status code and command execution.
    *
    * @param status The exist/status code of the executed command.
    * @param command The command that triggered the exception.
    */
  case class ToolException(status: Int, command: Seq[String]) extends RuntimeException {
    override def getMessage: String = s"Command failed with exit code $status: ${command.mkString(" ")}"
  }

  /** Execute a command while redirecting stdout or stderr streams elsewhere.
    *
    * @param command the command to run
    * @param logger an optional logger to use for emitting status updates
    * @param stdoutRedirect an optional function that will capture or sink away the stdout of the underlying process
    * @param stderrRedirect an optional function that will capture or sink away the stderr of the underlying process
    * @throws ToolException if command has a non-zero exit code
    */
  def execCommand(
    command: Seq[String],
    logger: Option[Logger]           = None,
    stdoutRedirect: String => Unit   = _ => (),
    stderrRedirect: String => Unit   = _ => (),
    environment: Map[String, String] = Map.empty,
  ): Unit = {
    logger.foreach(log => log.info(s"Executing command: ${command.mkString(" ")}"))
    val builder  = new ProcessBuilder(command: _*)

    builder.environment.putAll(environment.asJava)

    val process  = builder.start()
    val pipeOut  = new AsyncStreamSink(process.getInputStream, stdoutRedirect) // Get stdout
    val pipeErr  = new AsyncStreamSink(process.getErrorStream, stderrRedirect) // Get stderr
    val exitCode = process.waitFor()

    pipeOut.close()
    pipeErr.close()

    if (exitCode != 0) throw ToolException(exitCode, command)
  }

  /** Extracts a resource from the classpath and writes it to a temp file on disk.
    *
    * @param resource a given name on the classpath
    * @return path to the temporary file
    */
  private[io] def writeResourceToTempFile(resource: String): Path = {
    val stream = Seq(getClass.getResourceAsStream _, getClass.getClassLoader.getResourceAsStream _)
      .flatMap(get => Option(get(resource)))
      .headOption
      .getOrElse(throw new IllegalArgumentException(s"Resource does not exist at path: $resource"))

    val source = Source.fromInputStream(new BufferedInputStream(stream, 32 * 1024))
    val lines  = try source.getLines().toList finally source.close()
    val name   = this.getClass.getSimpleName.map(char => if (IllegalPathCharacters.contains(char)) "_" else char)
    val dir    = Files.createTempDirectory(name.mkString.substring(0, Math.min(name.length, MaxFileNameSize)))
    val path   = Paths.get(dir.toString, Paths.get(resource).getFileName.toString).toAbsolutePath.normalize

    dir.toFile.deleteOnExit()
    Files.write(path, lines.mkString(lineSeparator).getBytes(StandardCharsets.UTF_8))
    path
  }
}

/** A collection of values and methods specific for the Rscript executable */
object Rscript extends CliTool with VersionOnStdErr with Modular with ScriptRunner {

  /** Rscript executable. */
  val executable: String = "Rscript"

  /** The file extension for Rscript files. */
  val scriptSuffix: String = ".R"

  /** Command to test if a R module exists.
    *
    * @param module name of the module to be tested
    * @return command used to test if a given R module is installed
    */
  def testModuleCommand(module: String): Seq[String] = Seq(executable, "-e", s"stopifnot(require('$module'))")

  /** True if both Rscript exists and the library ggplot2 is installed. */
  lazy val ggplot2Available: Boolean = available && moduleAvailable("ggplot2")
}

/** Defines tools to test various version of Python executables */
trait Python extends CliTool with Versioned with Modular with ScriptRunner {

  /** Python executable. */
  val executable: String = "python"

  /** The file extension for Python files. */
  val scriptSuffix: String = ".py"

  /** The command to use to test the existence of a Python module. */
  def testModuleCommand(module: String): Seq[String] = Seq(executable, "-c", s"import $module")
}

/** The system Python version. */
object Python extends Python with VersionOnStream

/** The system Python 2. */
object Python2 extends Python with VersionOnStdErr { override val executable: String = "python2" }

/** The system Python3. */
object Python3 extends Python with VersionOnStdOut { override val executable: String = "python3" }
