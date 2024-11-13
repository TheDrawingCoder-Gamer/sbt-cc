/*
 *  Copyright (C) 2020 Takashi Nakamoto <nyakamoto@gmail.com>.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 3 as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package sbtcc

import java.io.File
import java.nio.file.Files

import scala.collection.immutable.ListSet
import scala.sys.process._
import scala.util.Try
import sbt.Keys._
import sbt._
import complete.DefaultParsers._
import sbt.internal.util.ManagedLogger

trait Target{
  val name: String
  def and(file: File): Target
}
trait Source{val file: File}

case class ExecutableAndSource(name: String, file: File) extends Target with Source {
  def and(file: File) = this
}
case class LibraryAndSource(name: String, file: File) extends Target with Source {
  def and(file: File) = this
}
case class SharedLibraryAndSource(name: String, file: File) extends Target with Source {
  def and(file: File) = this
}

case class Executable(name: String) extends Target { // Target which represents an executable.
  def and(file: File): ExecutableAndSource = {ExecutableAndSource(name, file)}
}
case class Library(name: String) extends Target { // Target which represents a static library (*.a).
  def and(file: File): LibraryAndSource = {LibraryAndSource(name, file)}
}
case class SharedLibrary(name: String) extends Target { // Target which represents a shared library (*.so).
  def and(file: File): SharedLibraryAndSource = {SharedLibraryAndSource(name, file)}
}

object CcPlugin extends AutoPlugin {
  override def requires = sbt.plugins.JvmPlugin

  object autoImport {
    lazy val ccTargets         = settingKey[ListSet[Target]]("Targets")

    lazy val cCompiler    = settingKey[String]("Command to compile C source files.")
    lazy val cFlags       = settingKey[Map[Target,Seq[String]]]("Flags to be passed to the C compiler.")
    lazy val cFlagsDynamic = taskKey[Map[Target, Seq[String]]]("Flags to be passed to the C compiler (dynamically defined)")
    lazy val cSources     = settingKey[Map[Target,Seq[File]]]("Path of C source files.")
    lazy val cSourceFiles = taskKey[Map[Target,Seq[File]]]("Path of C source files (dynamically defined).")
    lazy val cIncludes    = settingKey[Map[Target,Seq[File]]]("Directories to search C header files.")
    lazy val cIncludeDirectories = taskKey[Map[Target,Seq[File]]]("Directories to search C header files (dynamically defined.")

    lazy val cCompile     = inputKey[Seq[File]]("Input task to compile C source files for specific output(s).")
    lazy val cCompileForExecutables = taskKey[Seq[File]]("Task to compile C source files for all executables.")
    lazy val cCompileForLibraries = taskKey[Seq[File]]("Task to compile C source files for all executables.")
    lazy val cCompileForSharedLibraries = taskKey[Seq[File]]("Task to compile C source files for all executables.")

    lazy val cxxCompiler    = settingKey[String]("Command to compile C++ source files.")
    lazy val cxxFlags       = settingKey[Map[Target,Seq[String]]]("Flags to be passed to the C++ compiler.")
    lazy val cxxFlagsDynamic = taskKey[Map[Target,Seq[String]]]("Flags to be passed to the C++ compiler (dynamically defined)")
    lazy val cxxSources     = settingKey[Map[Target,Seq[File]]]("Path of C++ source files.")
    lazy val cxxSourceFiles = taskKey[Map[Target,Seq[File]]]("Path of C++ source files (dynamically defined).")
    lazy val cxxIncludes    = settingKey[Map[Target,Seq[File]]]("Directories to search C++ header files.")
    lazy val cxxIncludeDirectories = taskKey[Map[Target,Seq[File]]]("Directories to search C++ header files (dynamically defined.")

    lazy val cxxCompile     = inputKey[Seq[File]]("Input task to compile C++ source files for specific output(s).")
    lazy val cxxCompileForExecutables = taskKey[Seq[File]]("Task to compile C++ source files for all executables.")
    lazy val cxxCompileForLibraries = taskKey[Seq[File]]("Task to compile C++ source files for all executables.")
    lazy val cxxCompileForSharedLibraries = taskKey[Seq[File]]("Task to compile C++ source files for all executables.")

    lazy val ccArchiveCommand = settingKey[String]("Command to archive object files.")
    lazy val ccLinkerCommand = settingKey[String]("Command to link object files.")

    lazy val ldFlags = settingKey[Map[Target,Seq[String]]]("Flags to be passed to the compiler when linking.")
    lazy val ccLinkOnly    = inputKey[Seq[File]]("Input task to link object files and generate executables, static libraries and shared libraries. Does not compile dependent C source files.")
    lazy val ccLinkOnlyExecutables        = taskKey[Seq[File]]("Task to link object files and generate all executables. Does not compile dependent C source files.")
    lazy val ccLinkOnlyLibraries       = taskKey[Seq[File]]("Task to link object files and generate all static libraries. Does not compile dependent C source files.")
    lazy val ccLinkOnlySharedLibraries = taskKey[Seq[File]]("Task to link object files and generate all shared libraries. Does not compile dependent C source files.")
    lazy val ccLink        = inputKey[Seq[File]]("Input task to link object files and generate executables, static libraries and shared libraries.")
    lazy val ccLinkExecutables = taskKey[Seq[File]]("Task to link object files and generate all executables.")
    lazy val ccLinkLibraries       = taskKey[Seq[File]]("Task to link object files and all static libraries.")
    lazy val ccLinkSharedLibraries = taskKey[Seq[File]]("Task to link object files and all shared libraries.")

    lazy val ccSourceObjectMap = taskKey[Map[(Configuration, Target, File), File]]("Mapping from source files to object files.")
    lazy val ccTargetMap       = taskKey[Map[Target,File]]("Mapping from target names to the output paths of the targets.")

    lazy val ccRunExecutable = settingKey[Option[Executable]]("Executable to launch when 'run' command is issued.")
    lazy val runExecutable   = inputKey[Unit]("Task to run an executable.")

    lazy val ccDummyTask = taskKey[Unit]("Dummy task which does nothing.")
  }
  import autoImport._

  def isDescendent(directory: File, file: File): Boolean = {
    if (!directory.isDirectory) {
      throw new Exception(directory.toString + " is not a directory.")
    }

    file.toPath.startsWith(directory.toPath.toAbsolutePath)
  }

  def combineMaps[T,S](map1: Map[T,Seq[S]], map2: Map[T,Seq[S]]): Map[T,Seq[S]] = {
    (map1.toSeq ++ map2.toSeq).groupBy(_._1).mapValues(_.map(_._2).toList.flatten)
  }

  def cccompile(compiler: String,
              flags: Map[Target,Seq[String]],
              config: Configuration,
              targs : Set[Target],
              sources: Map[Target,Seq[File]],
              includes: Map[Target,Seq[File]],
              srcObjMap: Map[(Configuration, Target, File), File],
              logger: ManagedLogger
             ): Seq[File] = {
    var objs: List[File] = List()
    for (targ <- targs) {
      for (source <- sources.getOrElse(targ, Seq())) {
        val obj = srcObjMap((config, targ, source))
        val compileFlags = flags.getOrElse(targ and source, Try(flags(targ)).getOrElse(Seq()))
        val includeDirs  = includes.getOrElse(targ and source, Try(includes(targ)).getOrElse(Seq()))
        val cmd: Seq[String] = Seq(compiler, "-c", source.toString, "-o", obj.toString) ++ includeDirs.map(d => "-I" + d.toString) ++ compileFlags
        val p = Process(cmd)

        // compile only when the object file is older than the source file.
        if (obj.lastModified <= source.lastModified) {
          logger.info(p.toString)
          Files.createDirectories(obj.toPath.getParent)
          p.!!
        } else {
          // TODO: compile if the dependent header files are newer than the object file.
          logger.debug("Skip compiling " + source.toString)
        }

        objs ++= List(obj)
      }
    }
    objs
  }

  def link(compiler: String,
           archiver: String,
           flags: Map[Target,Seq[String]],
           config: Configuration,
           targs : Set[Target],
           sources: Map[Target,Seq[File]],
           srcObjMap: Map[(Configuration, Target, File), File],
           logger: ManagedLogger,
           targetMap: Map[Target, File],
          ): Seq[File] = {
    targs.map( targ => {
      val objs = sources.getOrElse(targ, Seq()).map(srcObjMap(config, targ, _))
      val ldflags = Try(flags(targ)).getOrElse(Seq())
      val output = targetMap(targ)

      // link only when the target is older than any of the object files.
      if (objs.exists(obj => output.lastModified <= obj.lastModified)) {
        val cmd: Seq[String] = targ match {
          case Executable(_) => Seq(compiler, "-o", output.toString) ++ objs.map(_.toString) ++ ldflags
          case Library(_) => Seq(archiver, "cr", output.toString) ++ objs.map(_.toString) ++ ldflags
          case SharedLibrary(_) => Seq(compiler, "-shared", "-o", output.toString) ++ objs.map(_.toString) ++ ldflags
        }

        val p = Process(cmd)
        logger.info(p.toString)

        Files.createDirectories(output.getParentFile.toPath)
        p.!!
      } else {
        logger.debug("Skip making " + output.toString)
      }

      output
    }).toSeq
  }

  def pickTarget(targs: Set[Target], names: Seq[String], logger: ManagedLogger): Set[Target] = {
    val invalidNames = names.filter(name => ! targs.map(_.name).contains(name))
    if (invalidNames.nonEmpty)
      logger.warn("Following targets in ccTargets do not exist: " + invalidNames.mkString(", "))

    if (names.isEmpty)
      targs
    else
      targs.filter(t => names.contains(t.name))
  }

  lazy val baseCcSettings: Seq[Def.Setting[_]] = Seq(
    ccTargets    := ListSet(),
    ccRunExecutable := Try(Some(ccTargets.value.filter(t => t.isInstanceOf[Executable]).map(t => Executable(t.name)).head)).getOrElse(None),

    cCompiler    := "cc",
    cFlags       := Map(),
    cFlagsDynamic := { cFlags.value },
    cSources     := Map(),
    cSourceFiles := { cSources.value },
    cIncludes    := Map(),
    cIncludeDirectories := { cIncludes.value },

    cxxCompiler    := "g++",
    cxxFlags       := Map(),
    cxxFlagsDynamic := { cxxFlags.value },
    cxxSources     := Map(),
    cxxSourceFiles := { cxxSources.value },
    cxxIncludes    := Map(),
    cxxIncludeDirectories := { cxxIncludes.value },

    ccArchiveCommand := "ar",
    ccLinkerCommand  := "g++",
    ldFlags := Map(),

    ccDummyTask := {},

    cCompile := {
      val targetNames: Seq[String] = spaceDelimited("<args>").parsed
      val compileTargets = pickTarget(ccTargets.value, targetNames, streams.value.log)

      cccompile(
        cCompiler.value,
        cFlagsDynamic.value,
        configuration.value,
        compileTargets,
        cSourceFiles.value,
        cIncludeDirectories.value,
        ccSourceObjectMap.value,
        streams.value.log,
      )
    },

    cCompileForExecutables := {
      cccompile(
        cCompiler.value,
        cFlagsDynamic.value,
        configuration.value,
        ccTargets.value.filter(t => t.isInstanceOf[Executable]),
        cSourceFiles.value,
        cIncludeDirectories.value,
        ccSourceObjectMap.value,
        streams.value.log,
      )
    },

    cCompileForLibraries := {
      cccompile(
        cCompiler.value,
        cFlagsDynamic.value,
        configuration.value,
        ccTargets.value.filter(t => t.isInstanceOf[Library]),
        cSourceFiles.value,
        cIncludeDirectories.value,
        ccSourceObjectMap.value,
        streams.value.log,
      )
    },

    cCompileForSharedLibraries := {
      cccompile(
        cCompiler.value,
        cFlagsDynamic.value,
        configuration.value,
        ccTargets.value.filter(t => t.isInstanceOf[SharedLibrary]),
        cSourceFiles.value,
        cIncludeDirectories.value,
        ccSourceObjectMap.value,
        streams.value.log,
      )
    },

    cxxCompile := {
      val targetNames: Seq[String] = spaceDelimited("<args>").parsed
      val compileTargets = pickTarget(ccTargets.value, targetNames, streams.value.log)

      cccompile(
        cxxCompiler.value,
        cxxFlagsDynamic.value,
        configuration.value,
        compileTargets,
        cxxSourceFiles.value,
        cxxIncludeDirectories.value,
        ccSourceObjectMap.value,
        streams.value.log,
      )
    },

    cxxCompileForExecutables := {
      cccompile(
        cxxCompiler.value,
        cxxFlagsDynamic.value,
        configuration.value,
        ccTargets.value.filter(t => t.isInstanceOf[Executable]),
        cxxSourceFiles.value,
        cxxIncludeDirectories.value,
        ccSourceObjectMap.value,
        streams.value.log,
      )
    },

    cxxCompileForLibraries := {
      cccompile(
        cxxCompiler.value,
        cxxFlagsDynamic.value,
        configuration.value,
        ccTargets.value.filter(t => t.isInstanceOf[Library]),
        cxxSourceFiles.value,
        cxxIncludeDirectories.value,
        ccSourceObjectMap.value,
        streams.value.log,
      )
    },

    cxxCompileForSharedLibraries := {
      cccompile(
        cxxCompiler.value,
        cxxFlagsDynamic.value,
        configuration.value,
        ccTargets.value.filter(t => t.isInstanceOf[SharedLibrary]),
        cxxSourceFiles.value,
        cxxIncludeDirectories.value,
        ccSourceObjectMap.value,
        streams.value.log,
      )
    },

    ccLinkOnly := {
      val targetNames: Seq[String] = spaceDelimited("<args>").parsed
      val linkTargets = pickTarget(ccTargets.value, targetNames, streams.value.log)

      link(
        ccLinkerCommand.value,
        ccArchiveCommand.value,
        ldFlags.value,
        configuration.value,
        linkTargets,
        combineMaps(cSourceFiles.value, cxxSourceFiles.value),
        ccSourceObjectMap.value,
        streams.value.log,
        ccTargetMap.value,
      )
    },

    ccLinkOnlyExecutables := {
      link(
        ccLinkerCommand.value,
        ccArchiveCommand.value,
        ldFlags.value,
        configuration.value,
        ccTargets.value.filter(t => t.isInstanceOf[Executable]),
        combineMaps(cSourceFiles.value, cxxSourceFiles.value),
        ccSourceObjectMap.value,
        streams.value.log,
        ccTargetMap.value,
      )
    },

    ccLinkOnlyLibraries := {
      link(
        ccLinkerCommand.value,
        ccArchiveCommand.value,
        ldFlags.value,
        configuration.value,
        ccTargets.value.filter(t => t.isInstanceOf[Library]),
        combineMaps(cSourceFiles.value, cxxSourceFiles.value),
        ccSourceObjectMap.value,
        streams.value.log,
        ccTargetMap.value,
      )
    },

    ccLinkOnlySharedLibraries := {
      link(
        ccLinkerCommand.value,
        ccArchiveCommand.value,
        ldFlags.value,
        configuration.value,
        ccTargets.value.filter(t => t.isInstanceOf[SharedLibrary]),
        combineMaps(cSourceFiles.value, cxxSourceFiles.value),
        ccSourceObjectMap.value,
        streams.value.log,
        ccTargetMap.value,
      )
    },

    ccLink := Def.inputTaskDyn {
      val args: Seq[String] = spaceDelimited("<args>").parsed
      Def.taskDyn {
        cCompile.toTask(" " + args.mkString(" ")).value
        cxxCompile.toTask(" " + args.mkString(" ")).value
        ccLinkOnly.toTask(" " + args.mkString(" "))
      }
    }.evaluated,

    ccLinkExecutables     := Def.sequential(cCompileForExecutables    , cxxCompileForExecutables    , ccLinkOnlyExecutables    ).value,
    ccLinkLibraries       := Def.sequential(cCompileForLibraries      , cxxCompileForLibraries      , ccLinkOnlyLibraries      ).value,
    ccLinkSharedLibraries := Def.sequential(cCompileForSharedLibraries, cxxCompileForSharedLibraries, ccLinkOnlySharedLibraries).value,

    ccSourceObjectMap := {
      val csrcs   = (cSourceFiles.value.map{ case (targ, files) => files.map((configuration.value, targ, _)) } toList).flatten
      val cxxsrcs = (cxxSourceFiles.value.map{ case (targ, files) => files.map((configuration.value, targ, _)) } toList).flatten

      if (csrcs.exists(cxxsrcs.contains)) {
        throw new Exception("cSourceFiles and cxxSourceFiles have common source files for the same target.")
      }

      val cacheDirectory = streams.value.cacheDirectory
      val ret = (csrcs ++ cxxsrcs).map { case (config, targ, src) =>

        val obj: File = if (isDescendent(baseDirectory.value, src)) {
          cacheDirectory / config.name / targ.name / (baseDirectory.value.toPath.relativize(src.toPath).toString + ".o")
        } else {
          val dirHash = f"${src.getParent.hashCode}%08x"
          cacheDirectory / config.name / targ.name / ("_" + dirHash) / (src.getName + ".o")

          // TODO: By any chance the generated file name may conflict with another.
          //       If conflict, change the dirHash to another or raise an Exception.
        }

        ((config, targ, src), obj)
      }.toMap

      ret
    },

    ccTargetMap := {
      val cacheDirectory = streams.value.cacheDirectory
      ccTargets.value.map(t => t -> (cacheDirectory / configuration.value.name / t.getClass.getName / t.name)).toMap
    },

    compile := (compile dependsOn ccLink.toTask("")).value,

    run := Def.inputTaskDyn {
      val args: Seq[String] = spaceDelimited("<args>").parsed
      val executable: Executable = ccRunExecutable.value match {
        case Some(p) => p
        case None => throw new Exception("ccRunExecutable is not defined.")
      }

      Def.taskDyn {
        // Compile and make the executable before run.
        ccLink.toTask(" " + executable.name).value

        val executablePath = ccTargetMap.value(executable)
        val process = Process(Seq(executablePath.toString) ++ args)
        streams.value.log.info(process.toString)
        process.!

        ccDummyTask
      }
    }.evaluated,

    runExecutable := Def.inputTaskDyn {
      val cmdAndArgs: Seq[String] = spaceDelimited("<args>").parsed
      val cmd: String = Try(cmdAndArgs.head).getOrElse("")
      if (cmd.isEmpty)
        throw new Exception("No executable name was specified.")

      val executable: Executable = Executable(cmd)
      val args: Seq[String] = cmdAndArgs.tail

      if (!ccTargets.value.contains(executable))
        throw new Exception("Executable target \"" + cmd + "\" is not defined.")


      Def.taskDyn {
        // Compile and make the executable before run.
        ccLink.toTask(" " + executable.name).value

        val executablePath = ccTargetMap.value(executable)
        val process = Process(Seq(executablePath.toString) ++ args)
        streams.value.log.info(process.toString)
        process.!

        ccDummyTask
      }
    }.evaluated,
  )

  override lazy val projectSettings = inConfig(Compile)(baseCcSettings) ++ inConfig(Test)(baseCcSettings)
}
