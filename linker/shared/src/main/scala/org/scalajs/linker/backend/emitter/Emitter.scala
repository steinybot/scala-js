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

package org.scalajs.linker.backend.emitter

import scala.annotation.tailrec

import scala.collection.mutable

import org.scalajs.ir.{ClassKind, Position, Version}
import org.scalajs.ir.Names._
import org.scalajs.ir.OriginalName.NoOriginalName
import org.scalajs.ir.Trees.{JSNativeLoadSpec, MemberNamespace}

import org.scalajs.logging._

import org.scalajs.linker.interface._
import org.scalajs.linker.standard._
import org.scalajs.linker.standard.ModuleSet.ModuleID
import org.scalajs.linker.backend.javascript.{Trees => js, _}
import org.scalajs.linker.CollectionsCompat.MutableMapCompatOps

import EmitterNames._
import GlobalRefUtils._

/** Emits a desugared JS tree to a builder */
final class Emitter(config: Emitter.Config) {

  import Emitter._
  import config._

  private val knowledgeGuardian = new KnowledgeGuardian(config)

  private val uncachedKnowledge = new knowledgeGuardian.KnowledgeAccessor {}

  private val nameGen: NameGen = new NameGen

  private class State(val lastMentionedDangerousGlobalRefs: Set[String]) {
    val sjsGen: SJSGen = {
      val jsGen = new JSGen(config)
      val varGen = new VarGen(jsGen, nameGen, lastMentionedDangerousGlobalRefs)
      new SJSGen(jsGen, nameGen, varGen)
    }

    val classEmitter: ClassEmitter = new ClassEmitter(sjsGen)

    val coreJSLibCache: CoreJSLibCache = new CoreJSLibCache

    val moduleCaches: mutable.Map[ModuleID, ModuleCache] = mutable.Map.empty

    val classCaches: mutable.Map[ClassID, ClassCache] = mutable.Map.empty
  }

  private var state: State = new State(Set.empty)

  private def jsGen: JSGen = state.sjsGen.jsGen
  private def sjsGen: SJSGen = state.sjsGen
  private def classEmitter: ClassEmitter = state.classEmitter
  private def classCaches: mutable.Map[ClassID, ClassCache] = state.classCaches

  private[this] var statsClassesReused: Int = 0
  private[this] var statsClassesInvalidated: Int = 0
  private[this] var statsMethodsReused: Int = 0
  private[this] var statsMethodsInvalidated: Int = 0

  val symbolRequirements: SymbolRequirement =
    Emitter.symbolRequirements(config)

  val injectedIRFiles: Seq[IRFile] = PrivateLibHolder.files

  def emit(moduleSet: ModuleSet, logger: Logger): Result = {
    val WithGlobals(body, globalRefs) = emitInternal(moduleSet, logger)

    moduleKind match {
      case ModuleKind.NoModule =>
        assert(moduleSet.modules.size <= 1)
        val topLevelVars = moduleSet.modules
          .headOption.toList
          .flatMap(_.topLevelExports)
          .map(_.exportName)

        val header = {
          val maybeTopLevelVarDecls = if (topLevelVars.nonEmpty) {
            val kw = if (esFeatures.useECMAScript2015Semantics) "let " else "var "
            topLevelVars.mkString(kw, ",", ";\n")
          } else {
            ""
          }
          config.jsHeader + maybeTopLevelVarDecls + "(function(){\n"
        }

        val footer = "}).call(this);\n"

        new Result(header, body, footer, topLevelVars, globalRefs)

      case ModuleKind.ESModule | ModuleKind.CommonJSModule =>
        new Result(config.jsHeader, body, "", Nil, globalRefs)
    }
  }

  private def emitInternal(moduleSet: ModuleSet,
      logger: Logger): WithGlobals[Map[ModuleID, List[js.Tree]]] = {
    // Reset caching stats.
    statsClassesReused = 0
    statsClassesInvalidated = 0
    statsMethodsReused = 0
    statsMethodsInvalidated = 0

    // Update GlobalKnowledge.
    val invalidateAll = knowledgeGuardian.update(moduleSet)
    if (invalidateAll) {
      state.coreJSLibCache.invalidate()
      classCaches.clear()
    }

    // Inform caches about new run.
    classCaches.valuesIterator.foreach(_.startRun())

    try {
      emitAvoidGlobalClash(moduleSet, logger, secondAttempt = false)
    } finally {
      // Report caching stats.
      logger.debug(
          s"Emitter: Class tree cache stats: reused: $statsClassesReused -- "+
          s"invalidated: $statsClassesInvalidated")
      logger.debug(
          s"Emitter: Method tree cache stats: reused: $statsMethodsReused -- "+
          s"invalidated: $statsMethodsInvalidated")

      // Inform caches about run completion.
      state.moduleCaches.filterInPlace((_, c) => c.cleanAfterRun())
      classCaches.filterInPlace((_, c) => c.cleanAfterRun())
    }
  }

  /** Emits all JavaScript code avoiding clashes with global refs.
   *
   *  If, at the end of the process, the set of accessed dangerous globals has
   *  changed, invalidate *everything* and start over. If at first you don't
   *  succeed, ...
   */
  @tailrec
  private def emitAvoidGlobalClash(moduleSet: ModuleSet,
      logger: Logger, secondAttempt: Boolean): WithGlobals[Map[ModuleID, List[js.Tree]]] = {
    val result = emitOnce(moduleSet, logger)

    val mentionedDangerousGlobalRefs =
      if (!trackAllGlobalRefs) result.globalVarNames
      else GlobalRefUtils.keepOnlyDangerousGlobalRefs(result.globalVarNames)

    if (mentionedDangerousGlobalRefs == state.lastMentionedDangerousGlobalRefs) {
      result
    } else {
      assert(!secondAttempt,
          "Uh oh! The second attempt gave a different set of dangerous " +
          "global refs than the first one.")

      // !!! This log message is tested in EmitterTest
      logger.debug(
          "Emitter: The set of dangerous global refs has changed. " +
          "Going to re-generate the world.")

      state = new State(mentionedDangerousGlobalRefs)
      emitAvoidGlobalClash(moduleSet, logger, secondAttempt = true)
    }
  }

  private def emitOnce(moduleSet: ModuleSet,
      logger: Logger): WithGlobals[Map[ModuleID, List[js.Tree]]] = {
    // Genreate classes first so we can measure time separately.
    val generatedClasses = logger.time("Emitter: Generate Classes") {
      moduleSet.modules.map { module =>
        val moduleContext = ModuleContext.fromModule(module)
        val orderedClasses = module.classDefs.sortWith(compareClasses)
        module.id -> orderedClasses.map(genClass(_, moduleContext))
      }.toMap
    }

    var trackedGlobalRefs = Set.empty[String]
    def extractWithGlobals[T](x: WithGlobals[T]) = {
      trackedGlobalRefs = unionPreserveEmpty(trackedGlobalRefs, x.globalVarNames)
      x.value
    }

    val moduleTrees = logger.time("Emitter: Write trees") {
      moduleSet.modules.map { module =>
        val moduleContext = ModuleContext.fromModule(module)
        val moduleCache = state.moduleCaches.getOrElseUpdate(module.id, new ModuleCache)

        val moduleClasses = generatedClasses(module.id)

        val moduleImports = extractWithGlobals {
          moduleCache.getOrComputeImports(module.externalDependencies, module.internalDependencies) {
            genModuleImports(module)
          }
        }

        val topLevelExports = extractWithGlobals {
          /* We cache top level exports all together, rather than individually,
           * since typically there are few.
           */
          moduleCache.getOrComputeTopLevelExports(module.topLevelExports) {
            classEmitter.genTopLevelExports(module.topLevelExports)(
                moduleContext, moduleCache)
          }
        }

        val moduleInitializers = extractWithGlobals {
          val initializers = module.initializers.toList
          moduleCache.getOrComputeInitializers(initializers) {
            WithGlobals.list(initializers.map { initializer =>
              classEmitter.genModuleInitializer(initializer)(
                  moduleContext, moduleCache)
            })
          }
        }

        val coreJSLib =
          if (module.isRoot) Some(extractWithGlobals(state.coreJSLibCache.build(moduleContext)))
          else None

        def classIter = moduleClasses.iterator

        def objectClass =
          if (!module.isRoot) Iterator.empty
          else classIter.filter(_.className == ObjectClass)

        /* Emit everything but module imports in the appropriate order.
         *
         * We do not emit module imports to be able to assert that the
         * resulting module is non-empty. This is a non-trivial condition that
         * requires consistency between the Analyzer and the Emitter. As such,
         * it is crucial that we verify it.
         */
        val defTrees: List[js.Tree] = (
            /* The definitions of the CoreJSLib that come before the definition
             * of `j.l.Object`. They depend on nothing else.
             */
            coreJSLib.iterator.flatMap(_.preObjectDefinitions) ++

            /* The definition of `j.l.Object` class. Unlike other classes, this
             * does not include its instance tests nor metadata.
             */
            objectClass.flatMap(_.main) ++

            /* The definitions of the CoreJSLib that come after the definition
             * of `j.l.Object` because they depend on it. This includes the
             * definitions of the array classes, as well as type data for
             * primitive types and for `j.l.Object`.
             */
            coreJSLib.iterator.flatMap(_.postObjectDefinitions) ++

            /* All class definitions, except `j.l.Object`, which depend on
             * nothing but their superclasses.
             */
            classIter.filterNot(_.className == ObjectClass).flatMap(_.main) ++

            /* The initialization of the CoreJSLib, which depends on the
             * definition of classes (n.b. the RuntimeLong class).
             */
            coreJSLib.iterator.flatMap(_.initialization) ++

            /* All static field definitions, which depend on nothing, except
             * those of type Long which need $L0.
             */
            classIter.flatMap(_.staticFields) ++

            /* All static initializers, which in the worst case can observe some
             * "zero" state of other static field definitions, but must not
             * observe a *non-initialized* (undefined) state.
             */
            classIter.flatMap(_.staticInitialization) ++

            /* All the exports, during which some JS class creation can happen,
             * causing JS static initializers to run. Those also must not observe
             * a non-initialized state of other static fields.
             */
            topLevelExports.iterator ++

            /* Module initializers, which by spec run at the end. */
            moduleInitializers.iterator
        ).toList

        // Make sure that there is at least one non-import definition.
        assert(!defTrees.isEmpty, {
            val classNames = module.classDefs.map(_.fullName).mkString(", ")
            s"Module ${module.id} is empty. Classes in this module: $classNames"
        })

        /* Add module imports, which depend on nothing, at the front.
         * All classes potentially depend on them.
         */
        val allTrees = moduleImports ::: defTrees

        classIter.foreach { genClass =>
          trackedGlobalRefs = unionPreserveEmpty(trackedGlobalRefs, genClass.trackedGlobalRefs)
        }

        module.id -> allTrees
      }
    }

    WithGlobals(moduleTrees.toMap, trackedGlobalRefs)
  }

  private def genModuleImports(module: ModuleSet.Module): WithGlobals[List[js.Tree]] = {
    implicit val pos = Position.NoPosition

    def importParts = (
        (
            module.externalDependencies.map { x =>
              sjsGen.varGen.externalModuleFieldIdent(x) -> x
            }
        ) ++ (
            module.internalDependencies.map { x =>
              sjsGen.varGen.internalModuleFieldIdent(x) -> config.internalModulePattern(x)
            }
        )
    ).toList.sortBy(_._1.name)

    moduleKind match {
      case ModuleKind.NoModule =>
        WithGlobals.nil

      case ModuleKind.ESModule =>
        val imports = importParts.map { case (ident, moduleName) =>
          val from = js.StringLiteral(moduleName)
          js.ImportNamespace(ident, from)
        }
        WithGlobals(imports)

      case ModuleKind.CommonJSModule =>
        val imports = importParts.map { case (ident, moduleName) =>
          for (requireRef <- jsGen.globalRef("require")) yield {
            val rhs = js.Apply(requireRef, List(js.StringLiteral(moduleName)))
            jsGen.genLet(ident, mutable = false, rhs)
          }
        }
        WithGlobals.list(imports)
    }
  }

  private def compareClasses(lhs: LinkedClass, rhs: LinkedClass) = {
    val lhsAC = lhs.ancestors.size
    val rhsAC = rhs.ancestors.size
    if (lhsAC != rhsAC) lhsAC < rhsAC
    else lhs.className.compareTo(rhs.className) < 0
  }

  private def genClass(linkedClass: LinkedClass,
      moduleContext: ModuleContext): GeneratedClass = {
    val className = linkedClass.className

    val classCache = classCaches.getOrElseUpdate(
        new ClassID(linkedClass.ancestors, moduleContext), new ClassCache)

    val classTreeCache =
      classCache.getCache(linkedClass.version)

    val kind = linkedClass.kind

    // Global ref management

    var trackedGlobalRefs: Set[String] = Set.empty

    def extractWithGlobals[T](withGlobals: WithGlobals[T]): T = {
      trackedGlobalRefs = unionPreserveEmpty(trackedGlobalRefs, withGlobals.globalVarNames)
      withGlobals.value
    }

    // Main part

    val main = List.newBuilder[js.Tree]

    val (linkedInlineableInit, linkedMethods) =
      classEmitter.extractInlineableInit(linkedClass)(classCache)

    // Symbols for private JS fields
    if (kind.isJSClass) {
      val fieldDefs = classTreeCache.privateJSFields.getOrElseUpdate {
        classEmitter.genCreatePrivateJSFieldDefsOfJSClass(className)(
            moduleContext, classCache)
      }
      main ++= extractWithGlobals(fieldDefs)
    }

    // Static-like methods
    for (methodDef <- linkedMethods) {
      val namespace = methodDef.flags.namespace

      val emitAsStaticLike = {
        namespace != MemberNamespace.Public ||
        kind == ClassKind.Interface ||
        kind == ClassKind.HijackedClass
      }

      if (emitAsStaticLike) {
        val methodCache =
          classCache.getStaticLikeMethodCache(namespace, methodDef.methodName)

        main ++= extractWithGlobals(methodCache.getOrElseUpdate(methodDef.version,
            classEmitter.genStaticLikeMethod(className, methodDef)(moduleContext, methodCache)))
      }
    }

    val isJSClass = kind.isJSClass

    // Class definition
    if (linkedClass.hasInstances && kind.isAnyNonNativeClass) {
      val isJSClassVersion = Version.fromByte(if (isJSClass) 1 else 0)

      /* Is this class compiled as an ECMAScript `class`?
       *
       * See JSGen.useClassesForRegularClasses for the rationale here.
       * Accessing `ancestors` without cache invalidation is fine because it
       * is part of the identity of a class for its cache in the first place.
       *
       * Note that `useClassesForRegularClasses` implies
       * `useClassesForJSClassesAndThrowables`, so the short-cut is valid.
       *
       * Compared to `ClassEmitter.shouldExtendJSError`, which is used below,
       * we do not check here that `Throwable` directly extends `Object`. If
       * that is not the case (for some obscure reason), then we are going to
       * uselessly emit `class`es for Throwables, but that will not make any
       * observable change; whereas rewiring Throwable to extend `Error` when
       * it does not actually directly extend `Object` would break everything,
       * so we need to be more careful there.
       *
       * For caching, isJSClassVersion can be used to guard use of `useESClass`:
       * it is the only "dynamic" value it depends on. The rest is configuration
       * or part of the cache key (ancestors).
       */
      val useESClass = if (jsGen.useClassesForRegularClasses) {
        assert(jsGen.useClassesForJSClassesAndThrowables)
        true
      } else {
        jsGen.useClassesForJSClassesAndThrowables &&
        (isJSClass || linkedClass.ancestors.contains(ThrowableClass))
      }

      // JS constructor
      val ctorWithGlobals = {
        /* The constructor depends both on the class version, and the version
         * of the inlineable init, if there is one.
         *
         * If it is a JS class, it depends on the jsConstructorDef.
         */
        val ctorCache = classCache.getConstructorCache()

        if (isJSClass) {
          assert(linkedInlineableInit.isEmpty)

          val jsConstructorDef = linkedClass.jsConstructorDef.getOrElse {
            throw new IllegalArgumentException(s"$className does not have an exported constructor")
          }

          val ctorVersion = Version.combine(linkedClass.version, jsConstructorDef.version)
          ctorCache.getOrElseUpdate(ctorVersion,
              classEmitter.genJSConstructor(
                className, // invalidated by overall class cache (part of ancestors)
                linkedClass.superClass, // invalidated by class version
                linkedClass.jsSuperClass, // invalidated by class version
                useESClass, // invalidated by class version
                jsConstructorDef // part of ctor version
              )(moduleContext, ctorCache, linkedClass.pos))
        } else {
          val ctorVersion = linkedInlineableInit.fold {
            Version.combine(linkedClass.version)
          } { linkedInit =>
            Version.combine(linkedClass.version, linkedInit.version)
          }

          ctorCache.getOrElseUpdate(ctorVersion,
              classEmitter.genScalaClassConstructor(
                className, // invalidated by overall class cache (part of ancestors)
                linkedClass.superClass, // invalidated by class version
                useESClass, // invalidated by class version,
                linkedInlineableInit // part of ctor version
              )(moduleContext, ctorCache, linkedClass.pos))
        }
      }

      /* Bridges from Throwable to methods of Object, which are necessary
       * because Throwable is rewired to extend JavaScript's Error instead of
       * j.l.Object.
       */
      val linkedMethodsAndBridges = if (ClassEmitter.shouldExtendJSError(className, linkedClass.superClass)) {
        val existingMethods = linkedMethods
          .withFilter(_.flags.namespace == MemberNamespace.Public)
          .map(_.methodName)
          .toSet

        val bridges = for {
          methodDef <- uncachedKnowledge.methodsInObject()
          if !existingMethods.contains(methodDef.methodName)
        } yield {
          import org.scalajs.ir.Trees._
          import org.scalajs.ir.Types._

          implicit val pos = methodDef.pos

          val methodName = methodDef.name
          val newBody = ApplyStatically(ApplyFlags.empty,
              This()(ClassType(className)), ObjectClass, methodName,
              methodDef.args.map(_.ref))(
              methodDef.resultType)
          MethodDef(MemberFlags.empty, methodName,
              methodDef.originalName, methodDef.args, methodDef.resultType,
              Some(newBody))(
              OptimizerHints.empty, methodDef.version)
        }

        linkedMethods ++ bridges
      } else {
        linkedMethods
      }

      // Normal methods
      val memberMethodsWithGlobals = for {
        method <- linkedMethodsAndBridges
        if method.flags.namespace == MemberNamespace.Public
      } yield {
        val methodCache =
          classCache.getMemberMethodCache(method.methodName)

        methodCache.getOrElseUpdate(method.version,
            classEmitter.genMemberMethod(className, method)(moduleContext, methodCache))
      }

      // Exported Members
      val exportedMembersWithGlobals = for {
        (member, idx) <- linkedClass.exportedMembers.zipWithIndex
      } yield {
        val memberCache = classCache.getExportedMemberCache(idx)
        val version = Version.combine(isJSClassVersion, member.version)
        memberCache.getOrElseUpdate(version,
            classEmitter.genExportedMember(
                className, // invalidated by overall class cache
                isJSClass, // invalidated by isJSClassVersion
                useESClass, // invalidated by isJSClassVersion
                member // invalidated by version
            )(moduleContext, memberCache))
      }

      val hasClassInitializer: Boolean = {
        linkedClass.methods.exists { m =>
          m.flags.namespace == MemberNamespace.StaticConstructor &&
          m.methodName.isClassInitializer
        }
      }

      val fullClass = {
        val fullClassCache = classCache.getFullClassCache()

        fullClassCache.getOrElseUpdate(linkedClass.version, ctorWithGlobals,
            memberMethodsWithGlobals, exportedMembersWithGlobals, {
          for {
            ctor <- ctorWithGlobals
            memberMethods <- WithGlobals.list(memberMethodsWithGlobals)
            exportedMembers <- WithGlobals.list(exportedMembersWithGlobals)
            clazz <- classEmitter.buildClass(
              className, // invalidated by overall class cache (part of ancestors)
              isJSClass, // invalidated by class version
              linkedClass.jsClassCaptures, // invalidated by class version
              hasClassInitializer, // invalidated by class version (optimizer cannot remove it)
              linkedClass.superClass, // invalidated by class version
              linkedClass.jsSuperClass, // invalidated by class version
              useESClass, // invalidated by class version (depends on kind, config and ancestry only)
              ctor, // invalidated directly
              memberMethods, // invalidated directly
              exportedMembers.flatten // invalidated directly
            )(moduleContext, fullClassCache, linkedClass.pos) // pos invalidated by class version
          } yield {
            clazz
          }
        })
      }

      main ++= extractWithGlobals(fullClass)
    }

    if (className != ObjectClass) {
      /* Instance tests and type data are hardcoded in the CoreJSLib for
       * j.l.Object. This is important because their definitions depend on the
       * `$TypeData` definition, which only comes in the `postObjectDefinitions`
       * of the CoreJSLib. If we wanted to define them here as part of the
       * normal logic of `ClassEmitter`, we would have to further divide `main`
       * into two parts. Since the code paths are in fact completely different
       * for `j.l.Object` anyway, we do not do this, and instead hard-code them
       * in the CoreJSLib. This explains why we exclude `j.l.Object` as this
       * level, rather than inside `ClassEmitter.needInstanceTests` and
       * similar: it is a concern that goes beyond the organization of the
       * class `j.l.Object`.
       */

      if (classEmitter.needInstanceTests(linkedClass)(classCache)) {
        main ++= extractWithGlobals(classTreeCache.instanceTests.getOrElseUpdate(
            classEmitter.genInstanceTests(className, kind)(moduleContext, classCache, linkedClass.pos)))
      }

      if (linkedClass.hasRuntimeTypeInfo) {
        main ++= extractWithGlobals(classTreeCache.typeData.getOrElseUpdate(
            classEmitter.genTypeData(
              className, // invalidated by overall class cache (part of ancestors)
              kind, // invalidated by class version
              linkedClass.superClass, // invalidated by class version
              linkedClass.ancestors, // invalidated by overall class cache (identity)
              linkedClass.jsNativeLoadSpec // invalidated by class version
            )(moduleContext, classCache, linkedClass.pos)))
      }

      if (linkedClass.hasInstances && kind.isClass && linkedClass.hasRuntimeTypeInfo) {
        main += classTreeCache.setTypeData.getOrElseUpdate(
            classEmitter.genSetTypeData(className)(moduleContext, classCache, linkedClass.pos))
      }
    }

    if (linkedClass.kind.hasModuleAccessor && linkedClass.hasInstances) {
      main ++= extractWithGlobals(classTreeCache.moduleAccessor.getOrElseUpdate(
          classEmitter.genModuleAccessor(className, isJSClass)(moduleContext, classCache, linkedClass.pos)))
    }

    // Static fields

    val staticFields = if (linkedClass.kind.isJSType) {
      Nil
    } else {
      extractWithGlobals(classTreeCache.staticFields.getOrElseUpdate(
          classEmitter.genCreateStaticFieldsOfScalaClass(className)(moduleContext, classCache)))
    }

    // Static initialization

    val staticInitialization = if (classEmitter.needStaticInitialization(linkedClass)) {
      classTreeCache.staticInitialization.getOrElseUpdate(
          classEmitter.genStaticInitialization(className)(moduleContext, classCache, linkedClass.pos))
    } else {
      Nil
    }

    // Build the result

    new GeneratedClass(
        className,
        main.result(),
        staticFields,
        staticInitialization,
        trackedGlobalRefs
    )
  }

  // Caching

  private final class ModuleCache extends knowledgeGuardian.KnowledgeAccessor {
    private[this] var _cacheUsed: Boolean = false

    private[this] var _importsCache: WithGlobals[List[js.Tree]] = WithGlobals.nil
    private[this] var _lastExternalDependencies: Set[String] = Set.empty
    private[this] var _lastInternalDependencies: Set[ModuleID] = Set.empty

    private[this] var _topLevelExportsCache: WithGlobals[List[js.Tree]] = WithGlobals.nil
    private[this] var _lastTopLevelExports: List[LinkedTopLevelExport] = Nil

    private[this] var _initializersCache: WithGlobals[List[js.Tree]] = WithGlobals.nil
    private[this] var _lastInitializers: List[ModuleInitializer.Initializer] = Nil

    override def invalidate(): Unit = {
      super.invalidate()

      /* In order to keep reasoning as local as possible, we also invalidate
       * the imports cache, although imports do not use any global knowledge.
       */
      _importsCache = WithGlobals.nil
      _lastExternalDependencies = Set.empty
      _lastInternalDependencies = Set.empty

      _topLevelExportsCache = WithGlobals.nil
      _lastTopLevelExports = Nil

      _initializersCache = WithGlobals.nil
      _lastInitializers = Nil
    }

    def getOrComputeImports(externalDependencies: Set[String], internalDependencies: Set[ModuleID])(
        compute: => WithGlobals[List[js.Tree]]): WithGlobals[List[js.Tree]] = {

      _cacheUsed = true

      if (externalDependencies != _lastExternalDependencies || internalDependencies != _lastInternalDependencies) {
        _importsCache = compute
        _lastExternalDependencies = externalDependencies
        _lastInternalDependencies = internalDependencies
      }
      _importsCache
    }

    def getOrComputeTopLevelExports(topLevelExports: List[LinkedTopLevelExport])(
        compute: => WithGlobals[List[js.Tree]]): WithGlobals[List[js.Tree]] = {

      _cacheUsed = true

      if (!sameTopLevelExports(topLevelExports, _lastTopLevelExports)) {
        _topLevelExportsCache = compute
        _lastTopLevelExports = topLevelExports
      }
      _topLevelExportsCache
    }

    private def sameTopLevelExports(tles1: List[LinkedTopLevelExport], tles2: List[LinkedTopLevelExport]): Boolean = {
      import org.scalajs.ir.Trees._

      /* Because of how/when we use this method, we already know that all the
       * `tles1` and `tles2` have the same `moduleID` (namely the ID of the
       * module represented by this `ModuleCache`). Therefore, we do not
       * compare that field.
       */

      tles1.corresponds(tles2) { (tle1, tle2) =>
        tle1.tree.pos == tle2.tree.pos && tle1.owningClass == tle2.owningClass && {
          (tle1.tree, tle2.tree) match {
            case (TopLevelJSClassExportDef(_, exportName1), TopLevelJSClassExportDef(_, exportName2)) =>
              exportName1 == exportName2
            case (TopLevelModuleExportDef(_, exportName1), TopLevelModuleExportDef(_, exportName2)) =>
              exportName1 == exportName2
            case (TopLevelMethodExportDef(_, methodDef1), TopLevelMethodExportDef(_, methodDef2)) =>
              methodDef1.version.sameVersion(methodDef2.version)
            case (TopLevelFieldExportDef(_, exportName1, field1), TopLevelFieldExportDef(_, exportName2, field2)) =>
              exportName1 == exportName2 && field1.name == field2.name && field1.pos == field2.pos
            case _ =>
              false
          }
        }
      }
    }

    def getOrComputeInitializers(initializers: List[ModuleInitializer.Initializer])(
        compute: => WithGlobals[List[js.Tree]]): WithGlobals[List[js.Tree]] = {

      _cacheUsed = true

      if (initializers != _lastInitializers) {
        _initializersCache = compute
        _lastInitializers = initializers
      }
      _initializersCache
    }

    def cleanAfterRun(): Boolean = {
      val result = _cacheUsed
      _cacheUsed = false
      result
    }
  }

  private final class ClassCache extends knowledgeGuardian.KnowledgeAccessor {
    private[this] var _cache: DesugaredClassCache = null
    private[this] var _lastVersion: Version = Version.Unversioned
    private[this] var _cacheUsed = false

    private[this] val _methodCaches =
      Array.fill(MemberNamespace.Count)(mutable.Map.empty[MethodName, MethodCache[List[js.Tree]]])

    private[this] val _memberMethodCache =
      mutable.Map.empty[MethodName, MethodCache[js.MethodDef]]

    private[this] var _constructorCache: Option[MethodCache[List[js.Tree]]] = None

    private[this] val _exportedMembersCache =
      mutable.Map.empty[Int, MethodCache[List[js.Tree]]]

    private[this] var _fullClassCache: Option[FullClassCache] = None

    override def invalidate(): Unit = {
      /* Do not invalidate contained methods, as they have their own
       * invalidation logic.
       */
      super.invalidate()
      _cache = null
      _lastVersion = Version.Unversioned
    }

    def startRun(): Unit = {
      _cacheUsed = false
      _methodCaches.foreach(_.valuesIterator.foreach(_.startRun()))
      _memberMethodCache.valuesIterator.foreach(_.startRun())
      _constructorCache.foreach(_.startRun())
      _fullClassCache.foreach(_.startRun())
    }

    def getCache(version: Version): DesugaredClassCache = {
      if (_cache == null || !_lastVersion.sameVersion(version)) {
        invalidate()
        statsClassesInvalidated += 1
        _lastVersion = version
        _cache = new DesugaredClassCache
      } else {
        statsClassesReused += 1
      }
      _cacheUsed = true
      _cache
    }

    def getMemberMethodCache(
        methodName: MethodName): MethodCache[js.MethodDef] = {
      _memberMethodCache.getOrElseUpdate(methodName, new MethodCache)
    }

    def getStaticLikeMethodCache(namespace: MemberNamespace,
        methodName: MethodName): MethodCache[List[js.Tree]] = {
      _methodCaches(namespace.ordinal)
        .getOrElseUpdate(methodName, new MethodCache)
    }

    def getConstructorCache(): MethodCache[List[js.Tree]] = {
      _constructorCache.getOrElse {
        val cache = new MethodCache[List[js.Tree]]
        _constructorCache = Some(cache)
        cache
      }
    }

    def getExportedMemberCache(idx: Int): MethodCache[List[js.Tree]] =
      _exportedMembersCache.getOrElseUpdate(idx, new MethodCache)

    def getFullClassCache(): FullClassCache = {
      _fullClassCache.getOrElse {
        val cache = new FullClassCache
        _fullClassCache = Some(cache)
        cache
      }
    }

    def cleanAfterRun(): Boolean = {
      _methodCaches.foreach(_.filterInPlace((_, c) => c.cleanAfterRun()))
      _memberMethodCache.filterInPlace((_, c) => c.cleanAfterRun())

      if (_constructorCache.exists(!_.cleanAfterRun()))
        _constructorCache = None

      _exportedMembersCache.filterInPlace((_, c) => c.cleanAfterRun())

      if (_fullClassCache.exists(!_.cleanAfterRun()))
        _fullClassCache = None

      if (!_cacheUsed)
        invalidate()

      _methodCaches.exists(_.nonEmpty) || _cacheUsed
    }
  }

  private final class MethodCache[T] extends knowledgeGuardian.KnowledgeAccessor {
    private[this] var _tree: WithGlobals[T] = null
    private[this] var _lastVersion: Version = Version.Unversioned
    private[this] var _cacheUsed = false

    override def invalidate(): Unit = {
      super.invalidate()
      _tree = null
      _lastVersion = Version.Unversioned
    }

    def startRun(): Unit = _cacheUsed = false

    def getOrElseUpdate(version: Version,
        v: => WithGlobals[T]): WithGlobals[T] = {
      if (_tree == null || !_lastVersion.sameVersion(version)) {
        invalidate()
        statsMethodsInvalidated += 1
        _tree = v
        _lastVersion = version
      } else {
        statsMethodsReused += 1
      }
      _cacheUsed = true
      _tree
    }

    def cleanAfterRun(): Boolean = {
      if (!_cacheUsed)
        invalidate()

      _cacheUsed
    }
  }

  private class FullClassCache extends knowledgeGuardian.KnowledgeAccessor {
    private[this] var _tree: WithGlobals[List[js.Tree]] = null
    private[this] var _lastVersion: Version = Version.Unversioned
    private[this] var _lastCtor: WithGlobals[List[js.Tree]] = null
    private[this] var _lastMemberMethods: List[WithGlobals[js.MethodDef]] = null
    private[this] var _lastExportedMembers: List[WithGlobals[List[js.Tree]]] = null
    private[this] var _cacheUsed = false

    override def invalidate(): Unit = {
      super.invalidate()
      _tree = null
      _lastVersion = Version.Unversioned
      _lastCtor = null
      _lastMemberMethods = null
      _lastExportedMembers = null
    }

    def startRun(): Unit = _cacheUsed = false

    def getOrElseUpdate(version: Version, ctor: WithGlobals[List[js.Tree]],
        memberMethods: List[WithGlobals[js.MethodDef]], exportedMembers: List[WithGlobals[List[js.Tree]]],
        compute: => WithGlobals[List[js.Tree]]): WithGlobals[List[js.Tree]] = {

      @tailrec
      def allSame[A <: AnyRef](xs: List[A], ys: List[A]): Boolean = {
        xs.isEmpty == ys.isEmpty && {
          xs.isEmpty ||
          ((xs.head eq ys.head) && allSame(xs.tail, ys.tail))
        }
      }

      if (_tree == null || !version.sameVersion(_lastVersion) || (_lastCtor ne ctor) ||
          !allSame(_lastMemberMethods, memberMethods) ||
          !allSame(_lastExportedMembers, exportedMembers)) {
        invalidate()
        _tree = compute
        _lastVersion = version
        _lastCtor = ctor
        _lastMemberMethods = memberMethods
        _lastExportedMembers = exportedMembers
      }

      _cacheUsed = true
      _tree
    }

    def cleanAfterRun(): Boolean = {
      if (!_cacheUsed)
        invalidate()

      _cacheUsed
    }
  }

  private class CoreJSLibCache extends knowledgeGuardian.KnowledgeAccessor {
    private[this] var _lastModuleContext: ModuleContext = _
    private[this] var _lib: WithGlobals[CoreJSLib.Lib] = _

    def build(moduleContext: ModuleContext): WithGlobals[CoreJSLib.Lib] = {
      if (_lib == null || _lastModuleContext != moduleContext) {
        _lib = CoreJSLib.build(sjsGen, moduleContext, this)
        _lastModuleContext = moduleContext
      }
      _lib
    }

    override def invalidate(): Unit = {
      super.invalidate()
      _lib = null
    }
  }
}

object Emitter {
  /** Result of an emitter run. */
  final class Result private[Emitter](
      val header: String,
      val body: Map[ModuleID, List[js.Tree]],
      val footer: String,
      val topLevelVarDecls: List[String],
      val globalRefs: Set[String]
  )

  /** Configuration for the Emitter. */
  final class Config private (
      val semantics: Semantics,
      val moduleKind: ModuleKind,
      val esFeatures: ESFeatures,
      val jsHeader: String,
      val internalModulePattern: ModuleID => String,
      val optimizeBracketSelects: Boolean,
      val trackAllGlobalRefs: Boolean
  ) {
    private def this(
        semantics: Semantics,
        moduleKind: ModuleKind,
        esFeatures: ESFeatures) = {
      this(
          semantics,
          moduleKind,
          esFeatures,
          jsHeader = "",
          internalModulePattern = "./" + _.id,
          optimizeBracketSelects = true,
          trackAllGlobalRefs = false)
    }

    def withSemantics(f: Semantics => Semantics): Config =
      copy(semantics = f(semantics))

    def withModuleKind(moduleKind: ModuleKind): Config =
      copy(moduleKind = moduleKind)

    def withESFeatures(f: ESFeatures => ESFeatures): Config =
      copy(esFeatures = f(esFeatures))

    def withJSHeader(jsHeader: String): Config = {
      require(StandardConfig.isValidJSHeader(jsHeader), jsHeader)
      copy(jsHeader = jsHeader)
    }

    def withInternalModulePattern(internalModulePattern: ModuleID => String): Config =
      copy(internalModulePattern = internalModulePattern)

    def withOptimizeBracketSelects(optimizeBracketSelects: Boolean): Config =
      copy(optimizeBracketSelects = optimizeBracketSelects)

    def withTrackAllGlobalRefs(trackAllGlobalRefs: Boolean): Config =
      copy(trackAllGlobalRefs = trackAllGlobalRefs)

    private def copy(
        semantics: Semantics = semantics,
        moduleKind: ModuleKind = moduleKind,
        esFeatures: ESFeatures = esFeatures,
        jsHeader: String = jsHeader,
        internalModulePattern: ModuleID => String = internalModulePattern,
        optimizeBracketSelects: Boolean = optimizeBracketSelects,
        trackAllGlobalRefs: Boolean = trackAllGlobalRefs): Config = {
      new Config(semantics, moduleKind, esFeatures, jsHeader,
          internalModulePattern, optimizeBracketSelects, trackAllGlobalRefs)
    }
  }

  object Config {
    def apply(coreSpec: CoreSpec): Config =
      new Config(coreSpec.semantics, coreSpec.moduleKind, coreSpec.esFeatures)
  }

  private final class DesugaredClassCache {
    val privateJSFields = new OneTimeCache[WithGlobals[List[js.Tree]]]
    val instanceTests = new OneTimeCache[WithGlobals[List[js.Tree]]]
    val typeData = new OneTimeCache[WithGlobals[List[js.Tree]]]
    val setTypeData = new OneTimeCache[js.Tree]
    val moduleAccessor = new OneTimeCache[WithGlobals[List[js.Tree]]]
    val staticInitialization = new OneTimeCache[List[js.Tree]]
    val staticFields = new OneTimeCache[WithGlobals[List[js.Tree]]]
  }

  private final class GeneratedClass(
      val className: ClassName,
      val main: List[js.Tree],
      val staticFields: List[js.Tree],
      val staticInitialization: List[js.Tree],
      val trackedGlobalRefs: Set[String]
  )

  private final class OneTimeCache[A >: Null] {
    private[this] var value: A = null
    def getOrElseUpdate(v: => A): A = {
      if (value == null)
        value = v
      value
    }
  }

  private case class ClassID(
      ancestors: List[ClassName], moduleContext: ModuleContext)

  private def symbolRequirements(config: Config): SymbolRequirement = {
    import config.semantics._
    import CheckedBehavior._

    val factory = SymbolRequirement.factory("emitter")
    import factory._

    def cond(p: Boolean)(v: => SymbolRequirement): SymbolRequirement =
      if (p) v else none()

    def isAnyFatal(behaviors: CheckedBehavior*): Boolean =
      behaviors.contains(Fatal)

    multiple(
        cond(asInstanceOfs != Unchecked) {
          instantiateClass(ClassCastExceptionClass, StringArgConstructorName)
        },

        cond(arrayIndexOutOfBounds != Unchecked) {
          instantiateClass(ArrayIndexOutOfBoundsExceptionClass,
              StringArgConstructorName)
        },

        cond(arrayStores != Unchecked) {
          instantiateClass(ArrayStoreExceptionClass,
              StringArgConstructorName)
        },

        cond(negativeArraySizes != Unchecked) {
          instantiateClass(NegativeArraySizeExceptionClass,
              NoArgConstructorName)
        },

        cond(nullPointers != Unchecked) {
          instantiateClass(NullPointerExceptionClass, NoArgConstructorName)
        },

        cond(stringIndexOutOfBounds != Unchecked) {
          instantiateClass(StringIndexOutOfBoundsExceptionClass,
              IntArgConstructorName)
        },

        cond(isAnyFatal(asInstanceOfs, arrayIndexOutOfBounds, arrayStores,
            negativeArraySizes, nullPointers, stringIndexOutOfBounds)) {
          instantiateClass(UndefinedBehaviorErrorClass,
              ThrowableArgConsructorName)
        },

        cond(moduleInit == Fatal) {
          instantiateClass(UndefinedBehaviorErrorClass,
              StringArgConstructorName)
        },

        // See systemIdentityHashCode in CoreJSLib
        callMethod(BoxedDoubleClass, hashCodeMethodName),
        callMethod(BoxedStringClass, hashCodeMethodName),

        cond(!config.esFeatures.allowBigIntsForLongs) {
          multiple(
              instanceTests(LongImpl.RuntimeLongClass),
              instantiateClass(LongImpl.RuntimeLongClass, LongImpl.AllConstructors.toList),
              callMethods(LongImpl.RuntimeLongClass, LongImpl.AllMethods.toList),
              callOnModule(LongImpl.RuntimeLongModuleClass, LongImpl.AllModuleMethods.toList)
          )
        }
    )
  }


}
