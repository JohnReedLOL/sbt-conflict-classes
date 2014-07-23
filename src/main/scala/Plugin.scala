package com.todesking.sbt_dependency_doctor

import java.io.File

case class Classpath(asFile:File) {
  def listResources():Seq[Resource] = {
    asFile match {
      case f if f.isDirectory => listFromDirectory(f)
      case f if f.isFile => listFromJar(f)
      case _ => Seq()
    }
  }

  private[this] def listFromDirectory(root:File, prefix:Seq[String] = Seq()):Seq[Resource] = {
    import scala.collection.JavaConverters._
    root.listFiles().flatMap { f =>
      f match {
        case f if(f.isFile) => Seq(Resource((prefix :+ f.getName).mkString("/")))
        case f if(f.isDirectory) => listFromDirectory(f, prefix :+ f.getName)
      }
    }
  }

  private[this] def listFromJar(file:File) = {
    import scala.collection.JavaConverters._
    new java.util.jar.JarFile(file)
      .entries.asScala
      .map { e => Resource(e.getName) }
      .toSeq
  }
}
case class Resource(name:String)
case class Conflict(resources:Set[Resource], classpathes:Set[Classpath])

object Plugin extends sbt.Plugin {
  import sbt._

  val conflictClasses = TaskKey[Unit]("conflict-classes", "show conflict classes in classpath")

  override lazy val settings =
    forConfig(Compile) ++ forConfig(Test) ++ forConfig(Runtime)

  def forConfig(config:Configuration) = inConfig(config)(Seq(
    conflictClasses <<= (Keys.dependencyClasspath in config) map { cps =>
      val conflicts = buildConflicts(cps.map(cp => Classpath(cp.data)))

      println("Listing conflict classes:")
      conflicts.foreach {conflict:Conflict =>
        println(s"Found conflict classes in:")
        conflict.classpathes.toSeq.sortBy(_.asFile.name).foreach { jar =>
          println(s"    ${jar.asFile}")
        }
        println(s"  with classes:")
        conflict.resources.toSeq.sortBy(_.name).foreach { entry =>
          println(s"    ${entry.name}")
        }
      }
    }
  ))

  def buildConflicts(cps:Seq[Classpath]):Seq[Conflict] = {
    val resourceToCps:Map[Resource, Seq[Classpath]] =
      cps.foldLeft(Map[Resource, Seq[Classpath]]()) {(map, cp) =>
        cp
          .listResources
          .filter { res =>
            !res.name.endsWith("/") &&
            !res.name.startsWith("META-INF/")
          }
          .foldLeft(map) {(map, res) => map + (res -> (map.getOrElse(res, Seq()) :+ cp)) }
      }

    val cpsToResources:Map[Set[Classpath], Set[Resource]] =
      resourceToCps.foldLeft(Map[Set[Classpath], Set[Resource]]()) { (map, kv) =>
      kv match { case (res, cps) =>
        val cpSet = cps.toSet
        map + (cpSet -> (map.getOrElse(cpSet, Set()) + res))
      }
    }

    cpsToResources.filter { case (cps, _) => cps.size > 1 }.map {case (cps, resources) => Conflict(resources, cps) }.toSeq
  }
}
