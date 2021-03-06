/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala
package tools.nsc

import io.File
import util.ClassPath
import interpreter.ILoop
import GenericRunnerCommand._

object JarRunner extends CommonRunner {
  def runJar(settings: GenericRunnerSettings, jarPath: String, arguments: Seq[String]): Either[Throwable, Boolean] = {
    val jar       = new io.Jar(jarPath)
    val mainClass = jar.mainClass getOrElse sys.error("Cannot find main class for jar: " + jarPath)
    val jarURLs   = ClassPath expandManifestPath jarPath
    val urls      = if (jarURLs.isEmpty) File(jarPath).toURL +: settings.classpathURLs else jarURLs

    if (settings.Ylogcp) {
      Console.err.println("Running jar with these URLs as the classpath:")
      urls foreach println
    }

    runAndCatch(urls, mainClass, arguments)
  }
}

/** An object that runs Scala code.  It has three possible
 *  sources for the code to run: pre-compiled code, a script file,
 *  or interactive entry.
 */
class MainGenericRunner {
  def errorFn(str: String, e: Option[Throwable] = None, isFailure: Boolean = true): Boolean = {
    if (str.nonEmpty) Console.err println str
    e foreach (_.printStackTrace())
    !isFailure
  }

  def process(args: Array[String]): Boolean = {
    val command = new GenericRunnerCommand(args.toList, (x: String) => errorFn(x))
    import command.{settings, howToRun, thingToRun, shortUsageMsg}

    // only created for info message
    def sampleCompiler = new Global(settings)

    def run(): Boolean = {
      def isE   = settings.execute.isSetByUser
      def dashe = settings.execute.value

      // when -e expr -howtorun script, read any -i or -I files and append expr
      // the result is saved to a tmp script file and run
      def combinedCode  = {
        val files   =
          for {
            dashi <- List(settings.loadfiles, settings.pastefiles) if dashi.isSetByUser
            path  <- dashi.value
          } yield File(path).slurp()

        (files :+ dashe).mkString("\n\n")
      }

      def runTarget(): Either[Throwable, Boolean] = howToRun match {
        case AsObject =>
          ObjectRunner.runAndCatch(settings.classpathURLs, thingToRun, command.arguments)
        case AsScript if isE =>
          Right(ScriptRunner.runCommand(settings, combinedCode, thingToRun +: command.arguments))
        case AsScript =>
          ScriptRunner.runScriptAndCatch(settings, thingToRun, command.arguments)
        case AsJar    =>
          JarRunner.runJar(settings, thingToRun, command.arguments)
        case Error =>
          Right(false)
        case _  =>
          // We start the repl when no arguments are given.
          // If user is agnostic about both -feature and -deprecation, turn them on.
          if (settings.deprecation.isDefault && settings.feature.isDefault) {
            settings.deprecation.value = true
            settings.feature.value = true
          }
          Right(new ILoop().process(settings))
      }

      runTarget() match {
        case Left(ex) => errorFn("", Some(ex))  // there must be a useful message of hope to offer here
        case Right(b) => b
      }
    }

    if (!command.ok)
      errorFn(f"%n$shortUsageMsg")
    else if (command.shouldStopWithInfo)
      errorFn(command.getInfoMessage(sampleCompiler), isFailure = false)
    else
      run()
  }
}

object MainGenericRunner extends MainGenericRunner {
  def main(args: Array[String]): Unit = if (!process(args)) sys.exit(1)
}
