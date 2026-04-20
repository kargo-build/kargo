package build.kargo.intellij.sync

import com.intellij.facet.Facet
import com.intellij.facet.FacetConfiguration
import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.FacetTypeRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.execution.RunManager
import build.kargo.intellij.execution.KargoRunConfiguration
import build.kargo.intellij.execution.KargoRunConfigurationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTable
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.roots.libraries.PersistentLibraryKind
import com.intellij.openapi.vfs.VfsUtilCore
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.Key
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.jetbrains.amper.dependency.resolution.diagnostics.detailedMessage
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.ResolutionLevel
import org.jetbrains.amper.dependency.resolution.diagnostics.PlatformsAreNotSupported
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.FragmentDependencyType
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.ancestralPath
import org.jetbrains.amper.frontend.fragmentsTargeting
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.AmperResolutionSettings
import org.jetbrains.amper.frontend.dr.resolver.DirectFragmentDependencyNode
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.resolveProjectDependencies
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencyNode
import org.jetbrains.amper.frontend.dr.resolver.ResolutionDepth
import org.jetbrains.amper.frontend.dr.resolver.ResolutionRunSettings
import org.jetbrains.amper.frontend.isDescendantOf
import java.io.File
import java.lang.reflect.Array
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Updates the IntelliJ WorkspaceModel based on the Kargo project model.
 */
class KargoWorkspaceModelUpdater(private val project: Project) {
    private val logger = Logger.getInstance(KargoWorkspaceModelUpdater::class.java)

    fun updateWorkspaceModel(model: Model, errorCollector: KargoSyncErrorCollector) {
        val app = ApplicationManager.getApplication()
        logger.info("Kargo: Scheduling WorkspaceModel update with ${model.modules.size} modules")

        // Git sources are now resolved by the frontend as LocalModuleDependency entries
        // injected into each fragment's externalDependencies. No separate processing needed here.

        // Resolve External Dependencies (Maven) on background thread.
        // This is a heavy operation (uses runBlocking) so it MUST NOT be on EDT.
        val fragmentToExternalDeps = resolveAllDependencies(model, errorCollector)

        app.invokeLater {
            if (project.isDisposed) return@invokeLater
            
            app.runWriteAction {
                try {
                    applyModel(model, fragmentToExternalDeps, errorCollector)
                    createDefaultRunConfigurations(model)
                    logger.info("Kargo: WorkspaceModel update completed")
                } catch (e: Exception) {
                    logger.error("Kargo: Failed to apply model to WorkspaceModel", e)
                    errorCollector.reportException(e)
                }
            }
        }
    }

    private fun applyModel(model: Model, fragmentToExternalDeps: Map<Fragment, Set<MavenDependencyNode>>, errorCollector: KargoSyncErrorCollector) {
        val moduleManager = ModuleManager.getInstance(project)
        
        ensureProjectSdk()

        // Cleanup old modules
        val moduleBasenames = model.modules.map { it.userReadableName }.toSet()
        val validModuleNames = model.modules.flatMap { km -> km.fragments.map { f -> "${km.userReadableName}.${f.name}" } }.toSet()

        val modulesToDispose = moduleManager.modules.filter { mod ->
            val name = mod.name
            if (name.startsWith("Kargo-")) return@filter true
            
            val dotIndex = name.indexOf('.')
            if (dotIndex > 0) {
                val basename = name.substring(0, dotIndex)
                if (moduleBasenames.contains(basename) && !validModuleNames.contains(name)) {
                    return@filter true
                }
            }
            false
        }

        modulesToDispose.forEach { 
            if (!it.isDisposed) {
                moduleManager.disposeModule(it) 
            }
        }

        // Create modules and map source roots
        val nameToModule = mutableMapOf<String, Module>()
        val modifiableModuleModel = moduleManager.getModifiableModel()
        
        model.modules.forEach { kargoModule ->
            kargoModule.fragments.forEach { fragment ->
                val moduleName = "${kargoModule.userReadableName}.${fragment.name}"
                val imlPath = project.basePath + "/.idea/modules/$moduleName.iml"

                try {
                    val ideModule = modifiableModuleModel.newModule(imlPath, "JAVA_MODULE")
                    nameToModule[moduleName] = ideModule

                    val modifiableModel = ModuleRootManager.getInstance(ideModule).modifiableModel
                    val isVendor = kargoModule.userReadableName.startsWith("vendor.")

                    fragment.sourceRoots.forEach { sourceRoot ->
                        val rootPath = sourceRoot.toString()
                        val sourceUrl = VfsUtilCore.pathToUrl(rootPath)

                        // For vendor (git source) modules, use the repo root as content root so the
                        // full repo is visible in the Project View. For project modules, use the
                        // source root directly to avoid duplicating the project root folder.
                        val contentRootPath = if (isVendor) {
                            Paths.get(rootPath).parent?.toString() ?: rootPath
                        } else {
                            rootPath
                        }
                        val contentUrl = VfsUtilCore.pathToUrl(contentRootPath)

                        val entry = if (modifiableModel.contentEntries.none { it.url == contentUrl }) {
                            modifiableModel.addContentEntry(contentUrl)
                        } else {
                            modifiableModel.contentEntries.first { it.url == contentUrl }
                        }

                        entry.addSourceFolder(sourceUrl, fragment.isTest)
                    }
                    
                    modifiableModel.inheritSdk()
                    modifiableModel.commit()

                    setupKotlinFacet(ideModule, moduleName, fragment)
                } catch (e: Exception) {
                    logger.error("Kargo: Failed to create module $moduleName", e)
                }
            }
        }
        modifiableModuleModel.commit()

        // External Dependencies (Maven)
        val allMavenNodes = fragmentToExternalDeps.values.flatten().toSet()

        // Detect if any maven node has klib files — those need KotlinNativeLibraryKind
        val kotlinNativeLibraryKind: com.intellij.openapi.roots.libraries.PersistentLibraryKind<*>? = try {
            @Suppress("UNCHECKED_CAST")
            Class.forName("org.jetbrains.kotlin.idea.base.platforms.KotlinNativeLibraryKind")
                .getField("INSTANCE").get(null) as? com.intellij.openapi.roots.libraries.PersistentLibraryKind<*>
        } catch (_: Exception) { null }

        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        val projectLibraryModel = libraryTable.modifiableModel
        val libraryMap = mutableMapOf<String, Library>()

        allMavenNodes.forEach { mavenNode ->
            val coords = mavenNode.dependency.coordinates
            val libraryName = "Kargo: ${coords.groupId}:${coords.artifactId}:${coords.version}"
            val hasKlib = mavenNode.dependency.files(false).any { it.path?.toString()?.endsWith(".klib") == true }

            // Always recreate the library roots to ensure stale entries from previous syncs are cleared.
            var library = libraryTable.getLibraryByName(libraryName)
            if (library == null) {
                library = if (hasKlib && kotlinNativeLibraryKind != null) {
                    projectLibraryModel.createLibrary(libraryName, kotlinNativeLibraryKind)
                } else {
                    projectLibraryModel.createLibrary(libraryName)
                }
            }
            val libModifiableModel = library.modifiableModel
            // Clear existing roots before re-adding to avoid duplicates from previous syncs
            libModifiableModel.getUrls(OrderRootType.CLASSES).forEach { libModifiableModel.removeRoot(it, OrderRootType.CLASSES) }
            libModifiableModel.getUrls(OrderRootType.SOURCES).forEach { libModifiableModel.removeRoot(it, OrderRootType.SOURCES) }

            mavenNode.dependency.files(withSources = true).forEach { depFile ->
                val path = depFile.path
                if (path != null && Files.exists(path)) {
                    val absPath = path.toAbsolutePath().toString()
                    val url = VfsUtilCore.pathToUrl(absPath)
                    val finalUrl = when {
                        absPath.endsWith(".jar") || absPath.endsWith(".zip") || absPath.endsWith(".klib") -> "jar://${absPath}!/"
                        else -> url
                    }
                    val rootType = if (depFile.isDocumentation) OrderRootType.SOURCES else OrderRootType.CLASSES
                    libModifiableModel.addRoot(finalUrl, rootType)
                }
            }
            libModifiableModel.commit()
            libraryMap[libraryName] = library
        }
        projectLibraryModel.commit()

        // --- Dependency Cleanup Phase ---
        // Clean slate: sweep old Kargo-managed library entries and module-to-module dependencies
        // before injecting the new dependency tree.
        model.modules.forEach { kargoModule ->
            kargoModule.fragments.forEach { fragment ->
                val moduleName = "${kargoModule.userReadableName}.${fragment.name}"
                val ideModule = nameToModule[moduleName] ?: return@forEach
                val modifiableModel = ModuleRootManager.getInstance(ideModule).modifiableModel
                var moduleChanged = false
                
                modifiableModel.orderEntries.forEach { entry ->
                    if (entry is ModuleOrderEntry) {
                        modifiableModel.removeOrderEntry(entry)
                        moduleChanged = true
                    } else if (entry is LibraryOrderEntry) {
                        val libName = entry.libraryName
                        if (libName != null && libName.startsWith("Kargo")) {
                            modifiableModel.removeOrderEntry(entry)
                            moduleChanged = true
                        }
                    }
                }
                if (moduleChanged) modifiableModel.commit() else modifiableModel.dispose()
            }
        }

        // Apply external dependencies to modules.
        // For each fragment that has resolved deps, also propagate to all ancestor fragments
        // (e.g. kermit resolved for linuxX64 must also be visible in linux, native, common).
        // This ensures source files in parent fragments can resolve symbols from leaf dependencies.
        val fragmentToExternalDepsExpanded = mutableMapOf<Fragment, MutableSet<MavenDependencyNode>>()
        fragmentToExternalDeps.forEach { (fragment, deps) ->
            fragment.ancestralPath().forEach { ancestor ->
                fragmentToExternalDepsExpanded.getOrPut(ancestor) { mutableSetOf() }.addAll(deps)
            }
        }

        model.modules.forEach { kargoModule ->
            kargoModule.fragments.forEach { fragment ->
                val moduleName = "${kargoModule.userReadableName}.${fragment.name}"
                val ideModule = nameToModule[moduleName] ?: return@forEach
                val externalDeps = fragmentToExternalDepsExpanded[fragment] ?: return@forEach

                val modifiableModel = ModuleRootManager.getInstance(ideModule).modifiableModel
                externalDeps.forEach { mavenNode ->
                    val coords = mavenNode.dependency.coordinates
                    val libraryName = "Kargo: ${coords.groupId}:${coords.artifactId}:${coords.version}"
                    val library = libraryMap[libraryName]
                    if (library != null) {
                        val existingEntry = modifiableModel.orderEntries.find { it is LibraryOrderEntry && it.libraryName == libraryName }
                        if (existingEntry == null) {
                            modifiableModel.addLibraryEntry(library)
                        }
                    }
                }
                modifiableModel.commit()
            }
        }
        
        // Inject Native default KLIBs
        injectNativeLibraries(model, nameToModule, libraryTable)

        // Intra-module Fragment Dependencies (within same module)
        model.modules.forEach { kargoModule ->
            kargoModule.fragments.forEach { fragment ->
                val moduleName = "${kargoModule.userReadableName}.${fragment.name}"
                val ideModule = nameToModule[moduleName] ?: return@forEach

                fragment.fragmentDependencies.forEach { link ->
                    val depName = "${kargoModule.userReadableName}.${link.target.name}"
                    val depModule = nameToModule[depName]
                    if (depModule != null) {
                        val isExported = link.type == FragmentDependencyType.REFINE
                        ModuleRootModificationUtil.addDependency(
                            ideModule, depModule, DependencyScope.COMPILE, isExported
                        )
                    }
                }
            }
        }

        // Inter-module Dependencies (local module deps like ./shared, ./infrastructure/server)
        applyLocalModuleDependencies(model, nameToModule, errorCollector)
    }

    /**
     * Wires local module dependencies (e.g. `./shared`, `./infrastructure/server` declared
     * in module.yaml) as IntelliJ module-to-module dependencies so that cross-module
     * references are resolved in the IDE.
     */
    private fun applyLocalModuleDependencies(
        model: Model,
        nameToModule: Map<String, Module>,
        errorCollector: KargoSyncErrorCollector
    ) {
        model.modules.forEach { kargoModule ->
            kargoModule.fragments.forEach { fragment ->
                val moduleName = "${kargoModule.userReadableName}.${fragment.name}"
                val ideModule = nameToModule[moduleName] ?: return@forEach

                fragment.externalDependencies
                    .filterIsInstance<LocalModuleDependency>()
                    .forEach { localDep ->
                        val targetKargoModule = localDep.module
                        // Find matching fragments in the target module that support this fragment's platforms
                        val targetFragments = targetKargoModule
                            .fragmentsTargeting(fragment.platforms, includeTestFragments = false)

                        targetFragments.forEach { targetFragment ->
                            val targetModuleName = "${targetKargoModule.userReadableName}.${targetFragment.name}"
                            val targetIdeModule = nameToModule[targetModuleName]
                            if (targetIdeModule != null) {
                                val scope = if (localDep.compile) DependencyScope.COMPILE else DependencyScope.RUNTIME
                                ModuleRootModificationUtil.addDependency(
                                    ideModule, targetIdeModule, scope, localDep.exported
                                )
                                logger.info("Kargo: Wired local module dep: $moduleName -> $targetModuleName")
                            } else {
                                logger.warn("Kargo: Could not find IDE module for local dep target: $targetModuleName")
                                errorCollector.reportError("<b>Local Dependency:</b> $targetModuleName<br>Could not find IDE module for local dependency target.")
                            }
                        }
                    }
            }
        }
    }

    private fun setupKotlinFacet(ideModule: Module, moduleName: String, fragment: Fragment) {
        try {
            val facetManager = FacetManager.getInstance(ideModule)
            val facetType = FacetTypeRegistry.getInstance().findFacetType("kotlin-language")
            if (facetType == null) {
                logger.warn("Kargo: kotlin-language facet type not found for $moduleName")
                return
            }

            val modifiableFacetModel = facetManager.createModifiableModel()
            modifiableFacetModel.getFacetByType(facetType.id)?.let { modifiableFacetModel.removeFacet(it) }

            @Suppress("UNCHECKED_CAST")
            val facet = facetManager.createFacet(
                facetType as FacetType<Facet<FacetConfiguration>, FacetConfiguration>,
                "Kotlin", null
            )

            val config = facet.configuration
            val kotlinCL = config.javaClass.classLoader
            val settings = config.javaClass.getMethod("getSettings").invoke(config)

            settings.javaClass.methods.find { it.name == "setUseProjectSettings" }?.invoke(settings, false)

            val isNative = fragment.platforms.any { it.isDescendantOf(Platform.NATIVE) }

            if (isNative) {
                // Configure Compiler Arguments
                try {
                    val argsClass = Class.forName("org.jetbrains.kotlin.cli.common.arguments.K2NativeCompilerArguments", true, kotlinCL)
                    val argsInstance = argsClass.getDeclaredConstructor().newInstance()
                    argsClass.getMethod("setMultiPlatform", Boolean::class.java).invoke(argsInstance, true)
                    try { argsClass.getMethod("setApiVersion", String::class.java).invoke(argsInstance, "2.2") } catch (_: Exception) {}
                    try { argsClass.getMethod("setLanguageVersion", String::class.java).invoke(argsInstance, "2.2") } catch (_: Exception) {}
                    settings.javaClass.methods.find { it.name == "setCompilerArguments" }?.invoke(settings, argsInstance)
                } catch (e: Exception) {
                    logger.warn("Kargo: Failed to set K2NativeCompilerArguments", e)
                }

                // Configure Target Platform
                try {
                    val targetClassName = when {
                        fragment.platforms.any { it.name.contains("MACOS", ignoreCase = true) } -> "org.jetbrains.kotlin.konan.target.KonanTarget\$MACOS_ARM64"
                        fragment.platforms.any { it.name.contains("MINGW", ignoreCase = true) } -> "org.jetbrains.kotlin.konan.target.KonanTarget\$MINGW_X64"
                        else -> "org.jetbrains.kotlin.konan.target.KonanTarget\$LINUX_X64"
                    }
                    val konanTargetClass = Class.forName("org.jetbrains.kotlin.konan.target.KonanTarget", true, kotlinCL)
                    val konanTarget = Class.forName(targetClassName, true, kotlinCL).getField("INSTANCE").get(null)
                    var nativePlatform: Any? = null
                    
                    try {
                        // Kotlin 2.0+ (NativePlatforms is a singleton object)
                        val platformsClass = Class.forName("org.jetbrains.kotlin.platform.konan.NativePlatforms", true, kotlinCL)
                        val platformsInstance = platformsClass.getField("INSTANCE").get(null)
                        
                        val bySingleTarget = platformsClass.methods.find { it.name == "nativePlatformBySingleTarget" }
                        if (bySingleTarget != null) {
                            nativePlatform = bySingleTarget.invoke(platformsInstance, konanTarget)
                        } else {
                            val byTargetsMethod = platformsClass.methods.find { it.name == "nativePlatformByTargets" }
                            nativePlatform = byTargetsMethod?.invoke(platformsInstance, listOf(konanTarget))
                        }
                    } catch (e: ClassNotFoundException) {
                        // Kotlin 1.9- (NativePlatformsKt has top-level functions)
                        val platformsKtClass = Class.forName("org.jetbrains.kotlin.platform.konan.NativePlatformsKt", true, kotlinCL)
                        val collectionMethod = platformsKtClass.methods.find { it.name == "nativePlatformByTargets" && it.parameterTypes.size == 1 && Collection::class.java.isAssignableFrom(it.parameterTypes[0]) }
                        if (collectionMethod != null) {
                            nativePlatform = collectionMethod.invoke(null, listOf(konanTarget))
                        } else {
                            val arrayMethod = platformsKtClass.methods.find { it.name == "nativePlatformByTargets" && it.parameterTypes.size == 1 && it.parameterTypes[0].isArray }
                            if (arrayMethod != null) {
                                val array = Array.newInstance(konanTargetClass, 1)
                                Array.set(array, 0, konanTarget)
                                nativePlatform = arrayMethod.invoke(null, array)
                            }
                        }
                    }

                    if (nativePlatform != null) {
                        settings.javaClass.methods.find { it.name == "setTargetPlatform" }?.invoke(settings, nativePlatform)
                        logger.info("Kargo: Set native target platform for $moduleName: $nativePlatform")
                    } else {
                        logger.warn("Kargo: Failed to obtain nativePlatform instance for Kotlin Facet on $moduleName")
                    }
                } catch (e: Exception) {
                    logger.warn("Kargo: Failed to set target platform in Kotlin Facet for $moduleName", e)
                }
            }

            modifiableFacetModel.addFacet(facet)
            modifiableFacetModel.commit()
            logger.info("Kargo: Kotlin facet committed for $moduleName isNative=$isNative")
        } catch (e: Exception) {
            logger.warn("Kargo: Failed to setup Kotlin facet for $moduleName", e)
        }
    }

    private fun resolveAllDependencies(model: Model, errorCollector: KargoSyncErrorCollector): Map<Fragment, Set<MavenDependencyNode>> {
        val fragmentToDeps = mutableMapOf<Fragment, MutableSet<MavenDependencyNode>>()

        val userCacheRootResult = AmperUserCacheRoot.fromCurrentUserResult()
        val userCacheRoot = if (userCacheRootResult is AmperUserCacheRoot) userCacheRootResult else null

        if (userCacheRoot == null) {
            logger.warn("Kargo: Could not initialize Amper user cache root. External dependencies resolution skipped.")
            return emptyMap()
        }

        val resolutionSettings = AmperResolutionSettings(
            userCacheRoot = userCacheRoot,
            includeNonExportedNative = false,
        )
        val resolutionRunSettings = ResolutionRunSettings(
            resolutionDepth = ResolutionDepth.GRAPH_FULL,
            resolutionLevel = ResolutionLevel.NETWORK,
        )

        try {
            runBlocking {
                val resolvedGraph = model.resolveProjectDependencies(resolutionSettings, resolutionRunSettings)
                val rootNode = resolvedGraph.root

                // Collect diagnostics from all nodes via BFS
                val diagQueue = ArrayDeque<org.jetbrains.amper.dependency.resolution.DependencyNode>()
                val diagVisited = mutableSetOf<org.jetbrains.amper.dependency.resolution.DependencyNode>()
                diagQueue.add(rootNode)
                while (diagQueue.isNotEmpty()) {
                    val node = diagQueue.removeFirst()
                    if (!diagVisited.add(node)) continue
                    diagQueue.addAll(node.children)

                    node.messages.forEach { message ->
                        if (message.severity >= Severity.WARNING) {
                            val isPlatformMismatch = message.id == PlatformsAreNotSupported.ID

                            val isDirect = node in rootNode.children
                            val isRoot = node == rootNode
                            val isTransitive = !isDirect && !isRoot

                            // Align with CLI: only report transitive diagnostics if allowed
                            if (isTransitive && !message.reportTransitive) return@forEach

                            val label = if (isPlatformMismatch) "Platform Compatibility" else "Maven Dependency"

                            val parent = node.parents.firstOrNull { it.graphEntryName.isNotBlank() }
                            val transitiveInfo = if (isTransitive && parent != null && parent != rootNode) {
                                " (transitive from ${parent.graphEntryName})"
                            } else ""

                            // Force platform mismatch to WARNING, otherwise follow message severity
                            val severity = if (isPlatformMismatch) SyncSeverity.WARNING
                                           else if (message.severity == Severity.ERROR) SyncSeverity.ERROR
                                           else SyncSeverity.WARNING

                            val content = message.detailedMessage.trim().replace("\n", "<br>")
                            errorCollector.reportError("<b>$label:</b> $content$transitiveInfo", severity)
                        }
                    }
                }

                // Map resolved Maven deps back to their fragments.
                // First, collect ALL resolved MavenDependencyNodes from the full graph (BFS from root),
                // indexed by key. This ensures we capture KMP variant nodes (e.g. kermit-linuxx64)
                // that are children of the KMP umbrella node (kermit) and have the actual .klib files.
                val allMavenNodesByKey = mutableMapOf<org.jetbrains.amper.dependency.resolution.Key<*>, MavenDependencyNode>()
                val globalQueue = ArrayDeque<org.jetbrains.amper.dependency.resolution.DependencyNode>()
                val globalVisited = mutableSetOf<org.jetbrains.amper.dependency.resolution.DependencyNode>()
                globalQueue.add(rootNode)
                while (globalQueue.isNotEmpty()) {
                    val current = globalQueue.removeFirst()
                    if (!globalVisited.add(current)) continue
                    if (current is MavenDependencyNode && current.dependency.files(false).isNotEmpty()) {
                        allMavenNodesByKey[current.key] = current
                    }
                    globalQueue.addAll(current.children)
                }

                rootNode.children.filterIsInstance<ModuleDependencyNode>().forEach { moduleNode ->
                    val kargoModule = model.modules.find { it.userReadableName == moduleNode.moduleName }
                    moduleNode.children.filterIsInstance<DirectFragmentDependencyNode>().forEach { fragmentNode ->
                        val fragment = kargoModule?.fragments?.find { it.name == fragmentNode.fragmentName }
                        if (fragment != null) {
                            val deps = fragmentToDeps.getOrPut(fragment) { mutableSetOf() }
                            // BFS from the direct dependency node, matching resolved nodes by key.
                            // For KMP libraries (e.g. kermit), the actual .klib is on a child node
                            // (e.g. kermit-linuxx64), not on the umbrella node itself.
                            val queue = ArrayDeque<DependencyNode>()
                            val visited = mutableSetOf<DependencyNode>()
                            queue.add(fragmentNode.dependencyNode)
                            while (queue.isNotEmpty()) {
                                val current = queue.removeFirst()
                                if (!visited.add(current)) continue
                                val resolved = allMavenNodesByKey[current.key]
                                if (resolved != null) {
                                    deps.add(resolved)
                                }
                                queue.addAll(current.children)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Kargo: Failed to resolve external dependencies", e)
            errorCollector.reportException(e)
        }

        return fragmentToDeps
    }
    
    private fun injectNativeLibraries(
        model: Model,
        nameToModule: Map<String, Module>,
        libraryTable: LibraryTable
    ) {
        val userHome = System.getProperty("user.home")
        val konanDir = File("$userHome/.konan")
        if (!konanDir.exists()) return

        val prebuiltDirs = konanDir.listFiles { f -> f.isDirectory && f.name.startsWith("kotlin-native-prebuilt-") }
        val latestPrebuilt = prebuiltDirs?.maxByOrNull { it.name } ?: return

        val kotlinNativeLibraryKind: PersistentLibraryKind<*>? = try {
            @Suppress("UNCHECKED_CAST")
            Class.forName("org.jetbrains.kotlin.idea.base.platforms.KotlinNativeLibraryKind")
                .getField("INSTANCE").get(null) as? PersistentLibraryKind<*>
        } catch (_: Exception) { null }

        val nativeLibMap = mutableMapOf<String, Library>()
        val projectLibraryModel = libraryTable.modifiableModel
        
        model.modules.forEach { kargoModule ->
            kargoModule.fragments.forEach { fragment ->
                if (fragment.platforms.any { it.isDescendantOf(Platform.NATIVE) }) {
                    val moduleName = "${kargoModule.userReadableName}.${fragment.name}"
                    val ideModule = nameToModule[moduleName] ?: return@forEach

                    val platformFolder = if (fragment.platforms.any { it.name.contains("LINUX") }) "linux_x64" 
                                         else if (fragment.platforms.any { it.name.contains("MACOS", ignoreCase=true) }) "macos_arm64"
                                         else "linux_x64" // fallback
                                         
                    val targetKlibDir = File(latestPrebuilt, "klib/platform/$platformFolder")
                    val commonKlibDir = File(latestPrebuilt, "klib/common/stdlib")
                    
                    val klibsToAttach = mutableListOf<File>()
                    if (commonKlibDir.exists()) klibsToAttach.add(commonKlibDir)
                    if (targetKlibDir.exists()) {
                        targetKlibDir.listFiles()?.forEach { klibsToAttach.add(it) }
                    }

                    val modifiableModel = ModuleRootManager.getInstance(ideModule).modifiableModel
                    
                    for (klibDir in klibsToAttach) {
                        val libraryName = "Kargo Native: ${klibDir.name} [$platformFolder]"

                        var library = nativeLibMap[libraryName] ?: libraryTable.getLibraryByName(libraryName)
                        if (library == null) {
                            library = if (kotlinNativeLibraryKind != null) {
                                projectLibraryModel.createLibrary(libraryName, kotlinNativeLibraryKind)
                            } else {
                                projectLibraryModel.createLibrary(libraryName)
                            }
                            val libModifiableModel = library.modifiableModel

                            val url = VfsUtilCore.pathToUrl(klibDir.absolutePath)
                            libModifiableModel.addRoot(url, OrderRootType.CLASSES)

                            libModifiableModel.commit()
                            nativeLibMap[libraryName] = library
                        }

                        val existingEntry = modifiableModel.orderEntries.find { it is LibraryOrderEntry && it.libraryName == libraryName }
                        if (existingEntry == null) {
                            modifiableModel.addLibraryEntry(library)
                        }
                    }
                    modifiableModel.commit()
                }
            }
        }
        projectLibraryModel.commit()
    }

    private fun ensureProjectSdk() {
        if (ProjectRootManager.getInstance(project).projectSdk != null) return

        try {
            val jdkTable = ProjectJdkTable.getInstance()
            var sdk = jdkTable.findMostRecentSdkOfType(jdkTable.defaultSdkType)
            if (sdk == null) {
                sdk = jdkTable.allJdks.firstOrNull()
            }

            if (sdk != null) {
                logger.info("Kargo: Automatically setting project SDK to ${sdk.name}")
                ProjectRootManager.getInstance(project).projectSdk = sdk
            } else {
                logger.warn("Kargo: No JDK found in the IDE. Please configure a JDK manually.")
            }
        } catch (e: Exception) {
        }
    }

    private fun createDefaultRunConfigurations(model: Model) {
        val runManager = RunManager.getInstance(project)
        val kargoConfigType = KargoRunConfigurationType.ID
        
        // Only proceed if there are no existing Kargo run configurations
        val existingConfigs = runManager.allSettings.filter { it.type.id == kargoConfigType }
        if (existingConfigs.isNotEmpty()) {
            logger.info("Kargo: Found ${existingConfigs.size} existing Run Configurations, skipping default creation")
            return
        }

        val projectPath = project.basePath?.let { Paths.get(it) } ?: return
        
        // Find a module that is exactly at the project root
        // We prioritize "app" over "lib" if multiple root modules are found (rare in Kargo)
        val rootModules = model.modules.filter { it.source.moduleDir == projectPath }
        if (rootModules.isEmpty()) {
            logger.info("Kargo: No module found at project root $projectPath")
            return
        }

        val targetModule = rootModules.find { !it.type.isLibrary() } ?: rootModules.first()
        val isApp = !targetModule.type.isLibrary()
        
        val factory = KargoRunConfigurationType().configurationFactories.first()
        val settings = runManager.createConfiguration("Run Kargo (Root)", factory)
        val configuration = settings.configuration as KargoRunConfiguration
        
        configuration.command = if (isApp) "run" else "build"
        configuration.workingDirectory = projectPath.toString()
        configuration.name = (if (isApp) "Run " else "Build ") + targetModule.userReadableName

        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings
        logger.info("Kargo: Created default Run Configuration for root module: ${configuration.name}")
    }
}
