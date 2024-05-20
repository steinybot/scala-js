/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package org.scalajs.linker.frontend.modulesplitter

import org.scalajs.ir.Names.ClassName
import org.scalajs.ir.SHA1
import org.scalajs.linker.frontend.modulesplitter.InternalModuleIDGenerator.ForDigests
import org.scalajs.linker.standard.ModuleSet.ModuleID

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.collection.{immutable, mutable}

/** FewestModulesTagger groups classes into coarse modules.
 *
 *  It is the primary mechanism for the FewestModulesAnalyzer but also used
 *  by the SmallModulesForAnalyzer.
 *
 *  To group classes into modules appropriately, we want to know for
 *  each class, "how" it can be reached. In practice, this means we
 *  record the path from the original public module and every
 *  dynamic import hop we made.
 *
 *  Of all these paths, we only care about the "simplest" ones. Or
 *  more formally, the minimum prefixes of all paths. For example,
 *  if a class is reachable by the following paths:
 *
 *  - a -> b
 *  - a -> b -> c
 *  - d -> c
 *  - d
 *
 *  We really only care about:
 *
 *  - a -> b
 *  - d
 *
 *  Because if we reach the class through a path that goes through
 *  `c`, it is necessarily already loaded.
 *
 *  Once we have obtained this minimal set of paths, we use the last
 *  element of each path to determine the final module
 *  grouping. This is because these have an actual static dependency
 *  on the node in question.
 *
 *  Merging these tags into a single `ModuleID` is delegated to the
 *  caller.
 */
private class FewestModulesTagger(infos: ModuleAnalyzer.DependencyInfo) {
  import FewestModulesTagger._

  private[this] val allPaths = mutable.Map.empty[ClassName, Paths]

  final def tagAll(modulesToAvoid: Iterable[ModuleID]): scala.collection.Map[ClassName, ModuleID] = {
    val internalModIDGenerator = new InternalModuleIDGenerator.ForDigests(modulesToAvoid)
    tagEntryPoints()
    for {
      (className, paths) <- allPaths
    } yield {
      className -> paths.moduleID(internalModIDGenerator)
    }
  }

  @tailrec
  private def tag(classNames: Set[ClassName], pathRoot: ModuleID, pathSteps: List[ClassName],
                  dynamics: Set[ClassName]): Set[ClassName] = {
    classNames.headOption match {
      case Some(className) => allPaths.get(className) match {
        case Some(paths) if !paths.hasDynamic && pathSteps.nonEmpty =>
          // Special case that visits static dependencies again when the first dynamic dependency is found so as to
          // ensure that they are not thought to only be used by a single public module.
          paths.put(pathRoot, pathSteps)
          val classInfo = infos.classDependencies(className)
          tag(classNames.tail ++ classInfo.staticDependencies, pathRoot, pathSteps, dynamics)
        case None =>
          val paths = new Paths
          paths.put(pathRoot, pathSteps)
          allPaths.put(className, paths)
          // Consider dependencies the first time we encounter them as this is the shortest path there will be.
          val classInfo = infos.classDependencies(className)
          tag(classNames.tail ++ classInfo.staticDependencies, pathRoot, pathSteps,
            dynamics ++ classInfo.dynamicDependencies)
        case Some(paths) =>
          paths.put(pathRoot, pathSteps)
          // Otherwise do not consider dependencies again as there is no more information to find.
          tag(classNames.tail, pathRoot, pathSteps, dynamics)
      }
      case None => dynamics
    }
  }

  @tailrec
  private def tagDynamics(classNames: Set[ClassName], pathRoot: ModuleID, pathSteps: List[ClassName],
                          dynamics: List[(List[ClassName], Set[ClassName])]): Unit = {
    classNames.headOption match {
      case Some(className) =>
        val nextPathSteps = pathSteps :+ className
        val relativeDynamics = tag(Set(className), pathRoot, nextPathSteps, Set.empty)
        val nextDynamics = nextPathSteps -> relativeDynamics :: dynamics
        tagDynamics(classNames.tail, pathRoot, pathSteps, nextDynamics)
      case None => dynamics match {
        case (pathSteps, classNames) :: remainingDynamics =>
          tagDynamics(classNames, pathRoot, pathSteps, remainingDynamics)
        case Nil => ()
      }
    }
  }

  private def tagEntryPoints(): Unit = {
    infos.publicModuleDependencies.foreach {
      case (moduleID, deps) =>
        val dynamics = tag(classNames = deps, pathRoot = moduleID, pathSteps = Nil, dynamics = Set.empty)
        tagDynamics(classNames = dynamics, pathRoot = moduleID, pathSteps = Nil, dynamics = Nil)
    }
  }
}

private object FewestModulesTagger {

  /** "Interesting" paths that can lead to a given class.
   *
   * "Interesting" in this context means:
   *  - All direct paths from a public dependency.
   *  - All non-empty, mutually prefix-free paths of dynamic import hops.
   */
  private final class Paths {
    private val direct = mutable.Set.empty[ModuleID]
    private val dynamic = mutable.Map.empty[ModuleID, DynamicPaths]

    def hasDynamic: Boolean = dynamic.nonEmpty

    def put(pathRoot: ModuleID, pathSteps: List[ClassName]): Boolean = {
      if (pathSteps.isEmpty) {
        direct.add(pathRoot)
      } else {
        dynamic
          .getOrElseUpdate(pathRoot, new DynamicPaths)
          .put(pathSteps)
      }
    }

    def moduleID(internalModIDGenerator: ForDigests): ModuleID = {
      if (direct.size == 1 && dynamic.isEmpty) {
        /* Class is only used by a single public module. Put it there.
         *
         * Note that we must not do this if there are any dynamic
         * modules requiring this class. Otherwise, the dynamically loaded module
         * will try to import the public module (but importing public modules is
         * forbidden).
         */
        direct.head
      } else {
        /* Class is used by multiple public modules and/or dynamic edges.
         * Create a module ID grouping it with other classes that have the same
         * dependees.
         */
        val digestBuilder = new SHA1.DigestBuilder

        // Public modules using this.
        for (id <- direct.toList.sortBy(_.id))
          digestBuilder.update(id.id.getBytes(StandardCharsets.UTF_8))

        // Dynamic modules using this.
        for (className <- dynamicEnds)
          digestBuilder.updateUTF8String(className.encoded)

        internalModIDGenerator.forDigest(digestBuilder.finalizeDigest())
      }
    }

    private def intToBytes(x: Int): Array[Byte] = {
      val result = new Array[Byte](4)
      val buf = ByteBuffer.wrap(result)
      buf.putInt(x)
      result
    }

    private def dynamicEnds: immutable.SortedSet[ClassName] = {
      val builder = immutable.SortedSet.newBuilder[ClassName]
      /* We ignore paths that originate in a module that imports this class
       * directly: They are irrelevant for the final ID.
       *
       * However, they are important to ensure we do not attempt to import a
       * public module (see the comment in moduleID); therefore, we only filter
       * them here.
       */
      for ((h, t) <- dynamic if !direct.contains(h))
        t.ends(builder)
      builder.result()
    }
  }

  /** Set of shortest, mutually prefix-free paths of dynamic import hops */
  private final class DynamicPaths {
    private val content = mutable.Map.empty[ClassName, DynamicPaths]

    @tailrec
    def put(path: List[ClassName]): Boolean = {
      val h :: t = path

      if (content.get(h).exists(_.content.isEmpty)) {
        // shorter or equal path already exists.
        false
      } else if (t.isEmpty) {
        // the path we put stops here, prune longer paths (if any).
        content.put(h, new DynamicPaths)
        true
      } else {
        // there are other paths, recurse.
        content
          .getOrElseUpdate(h, new DynamicPaths)
          .put(t)
      }
    }

    /** Populates `builder` with the ends of all paths. */
    def ends(builder: mutable.Builder[ClassName, Set[ClassName]]): Unit = {
      for ((h, t) <- content) {
        if (t.content.isEmpty)
          builder += h
        else
          t.ends(builder)
      }
    }
  }
}
