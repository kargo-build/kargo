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
import com.intellij.openapi.vfs.VfsUtilCore
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.ResolutionLevel
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.FragmentDependencyType
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.fragmentsTargeting
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.GitSourcesModulePart
import org.jetbrains.amper.frontend.dr.resolver.DependenciesFlowType
import org.jetbrains.amper.frontend.dr.resolver.DirectFragmentDependencyNode
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencyNode
import org.jetbrains.amper.frontend.dr.resolver.ResolutionDepth
import org.jetbrains.amper.frontend.dr.resolver.ResolutionInput
import org.jetbrains.amper.frontend.dr.resolver.getAmperFileCacheBuilder
import org.jetbrains.amper.frontend.dr.resolver.moduleDependenciesResolver
import org.jetbrains.amper.frontend.isDescendantOf
import java.io.File
import java.lang.reflect.Array
import java.nio.file.Files
import java.nio.file.Paths
import build.kargo.frontend.dr.resolver.GitSourcesExtension
import build.kargo.frontend.schema.BitbucketSource
import build.kargo.frontend.schema.GitHubSource
import build.kargo.frontend.schema.GitLabSource
import build.kargo.frontend.schema.GitSource
import build.kargo.frontend.schema.GitUrlSource
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Updates the IntelliJ WorkspaceModel based on the Kargo project model.
 */
class KargoWorkspaceModelUpdater(private val project: Project) {
    private val logger = Logger.getInstance(KargoWorkspaceModelUpdater::class.java)

    fun updateWorkspaceModel(model: Model) {
        val app = ApplicationManager.getApplication()
        logger.info("Kargo: Scheduling WorkspaceModel update with ${model.modules.size} modules")
        
        // 1. Process Git Sources synchronously on background thread
        // We do this BEFORE invokeLater to prevent blocking the UI/EDT
        // Clear stale cache so removed/added Git deps are detected on every sync
        GitSourcesExtension.clearCache()
        val gitErrors = mutableListOf<String>()
        model.modules.forEach { kargoModule ->
            val allPlatforms = kargoModule.fragments.flatMap { it.platforms.toList() }.distinct()
            
            GitSourcesExtension.processModuleGitSources(kargoModule, allPlatforms) { source, exception ->
                val repoRef = when (source) {
                    is GitHubSource -> "github:${source.github}:${source.version.value}"
                    is GitLabSource -> "gitlab:${source.gitlab}:${source.version.value}"
                    is BitbucketSource -> "bitbucket:${source.bitbucket}:${source.version.value}"
                    is GitUrlSource -> "${source.git}:${source.version.value}"
                    else -> "unknown source"
                }
                logger.warn("Kargo: Could not resolve Git dependency '$repoRef': ${exception.message}")
                gitErrors.add("Could not resolve Git dependency '$repoRef'")
            }
        }
        
        // If there are errors, show Balloon notification (Gradle-style)
        if (gitErrors.isNotEmpty()) {
            val message = gitErrors.joinToString("<br>") { "• $it" }
            app.invokeLater {
                if (!project.isDisposed) {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Kargo")
                        .createNotification(
                            "Kargo Sync: dependency resolution failed",
                            message,
                            NotificationType.ERROR
                        )
                        .notify(project)
                }
            }
        }
        
        app.invokeLater {
            if (project.isDisposed) return@invokeLater
            
            app.runWriteAction {
                try {
                    applyModel(model)
                    createDefaultRunConfigurations(model)
                    logger.info("Kargo: WorkspaceModel update completed")
                } catch (e: Exception) {
                    logger.error("Kargo: Failed to apply model to WorkspaceModel", e)
                }
            }
        }
    }

    private fun applyModel(model: Model) {
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
                    modifiableModuleModel.setModuleGroupPath(ideModule, arrayOf(kargoModule.userReadableName))
                    nameToModule[moduleName] = ideModule

                    val modifiableModel = ModuleRootManager.getInstance(ideModule).modifiableModel
                    
                    // Derive the module root directory from the fragment's source root parent
                    // This prevents IntelliJ from rendering visual grouping brackets in the Project View
                    val firstSourceRoot = fragment.sourceRoots.firstOrNull()?.toString()
                    val moduleRootPath = firstSourceRoot?.let { Paths.get(it).parent?.toString() }
                    
                    val contentEntry = if (moduleRootPath != null) {
                        val moduleBaseUrl = VfsUtilCore.pathToUrl(moduleRootPath)
                        if (modifiableModel.contentEntries.none { it.url == moduleBaseUrl }) {
                            modifiableModel.addContentEntry(moduleBaseUrl)
                        } else {
                            modifiableModel.contentEntries.first { it.url == moduleBaseUrl }
                        }
                    } else null
                    
                    fragment.sourceRoots.forEach { sourceRoot ->
                        val rootPath = sourceRoot.toString()
                        val url = VfsUtilCore.pathToUrl(rootPath)
                        
                        val entryToUse = contentEntry ?: if (modifiableModel.contentEntries.none { it.url == url }) {
                            modifiableModel.addContentEntry(url)
                        } else {
                            modifiableModel.contentEntries.first { it.url == url }
                        }
                        
                        entryToUse.addSourceFolder(url, fragment.isTest)
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
        val fragmentToExternalDeps = resolveAllDependencies(model)
        val allMavenNodes = fragmentToExternalDeps.values.flatten().toSet()
        
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        val projectLibraryModel = libraryTable.modifiableModel
        val libraryMap = mutableMapOf<String, Library>()
        
        allMavenNodes.forEach { mavenNode ->
            val coords = mavenNode.dependency.coordinates
            val libraryName = "Kargo: ${coords.groupId}:${coords.artifactId}:${coords.version}"
            
            var library = libraryTable.getLibraryByName(libraryName)
            if (library == null) {
                library = projectLibraryModel.createLibrary(libraryName)
                val libModifiableModel = library.modifiableModel
                
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
            }
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

        // Apply external dependencies to modules
        model.modules.forEach { kargoModule ->
            kargoModule.fragments.forEach { fragment ->
                val moduleName = "${kargoModule.userReadableName}.${fragment.name}"
                val ideModule = nameToModule[moduleName] ?: return@forEach
                val externalDeps = fragmentToExternalDeps[fragment] ?: return@forEach
                
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
        
        // Inject Git Sources Dependencies
        // Clean up old Kargo Git libraries from the LibraryTable
        val gitLibraryModel = libraryTable.modifiableModel
        val gitLibsToRemove = libraryTable.libraries.filter { it.name?.startsWith("Kargo Git:") == true || it.name?.startsWith("Kargo Local Git:") == true }

        gitLibsToRemove.forEach { lib ->
            gitLibraryModel.removeLibrary(lib)
        }
        gitLibraryModel.commit()
        // 1. Create all Git libraries and commit the project library model
        val gitLibMap = mutableMapOf<String, Library>()
        val gitLibraryModelNew = libraryTable.modifiableModel
        
        model.modules.forEach { kargoModule ->
            val gitArtifacts = GitSourcesExtension.getArtifacts(kargoModule)
            gitArtifacts.forEach { artifact ->
                val absPath = artifact.artifactPath.toAbsolutePath().toString()
                val source = artifact.source
                val repoName = when (source) {
                    is GitHubSource -> source.github
                    is GitLabSource -> source.gitlab
                    is BitbucketSource -> source.bitbucket
                    is GitUrlSource -> source.git.substringAfterLast("/").removeSuffix(".git")
                }
                val version = source.version.value
                val libraryName = "Kargo Git: $repoName:$version"
                
                var library = gitLibMap[libraryName] ?: libraryTable.getLibraryByName(libraryName)
                if (library == null) {
                    library = gitLibraryModelNew.createLibrary(libraryName)
                    val libModifiableModel = library.modifiableModel
                    
                    val url = VfsUtilCore.pathToUrl(absPath)
                    val finalUrl = if (absPath.endsWith(".klib")) "jar://${absPath}!/" else url
                    libModifiableModel.addRoot(finalUrl, OrderRootType.CLASSES)
                    
                    libModifiableModel.commit()
                    gitLibMap[libraryName] = library
                }
            }
        }
        gitLibraryModelNew.commit()

        // 2. Assign the created libraries to their respective modules
        model.modules.forEach { kargoModule ->
            val gitArtifacts = GitSourcesExtension.getArtifacts(kargoModule)
            logger.info("Kargo: Module ${kargoModule.userReadableName} has ${gitArtifacts.size} Git artifacts to inject")
            kargoModule.fragments.forEach { fragment ->
                val moduleName = "${kargoModule.userReadableName}.${fragment.name}"
                val ideModule = nameToModule[moduleName] ?: return@forEach
                
                val modifiableModel = ModuleRootManager.getInstance(ideModule).modifiableModel
                var changed = false
                gitArtifacts.forEach { artifact ->
                    val source = artifact.source
                    val repoName = when (source) {
                        is GitHubSource -> source.github
                        is GitLabSource -> source.gitlab
                        is BitbucketSource -> source.bitbucket
                        is GitUrlSource -> source.git.substringAfterLast("/").removeSuffix(".git")
                    }
                    val version = source.version.value
                    val libraryName = "Kargo Git: $repoName:$version"
                    
                    val library = gitLibMap[libraryName] ?: libraryTable.getLibraryByName(libraryName)
                    val existingEntry = modifiableModel.orderEntries.find { it is LibraryOrderEntry && it.libraryName == libraryName }
                    if (existingEntry == null && library != null) {
                        modifiableModel.addLibraryEntry(library)
                        changed = true
                    }
                }
                
                if (changed) modifiableModel.commit() else modifiableModel.dispose()
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
        applyLocalModuleDependencies(model, nameToModule)
    }

    /**
     * Wires local module dependencies (e.g. `./shared`, `./infrastructure/server` declared
     * in module.yaml) as IntelliJ module-to-module dependencies so that cross-module
     * references are resolved in the IDE.
     */
    private fun applyLocalModuleDependencies(
        model: Model,
        nameToModule: Map<String, Module>
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
                            }
                        }
                    }
            }
        }
    }

    private fun setupKotlinFacet(ideModule: Module, moduleName: String, fragment: Fragment) {
        try {
            val facetManager = FacetManager.getInstance(ideModule)
            val facetType = FacetTypeRegistry.getInstance().findFacetType("kotlin-language") ?: return

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
                        val collectionMethod = platformsKtClass.methods.find { it.name == "nativePlatformByTargets" && it.parameterTypes.size == 1 && java.util.Collection::class.java.isAssignableFrom(it.parameterTypes[0]) }
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
                    } else {
                        logger.warn("Kargo: Failed to obtain nativePlatform instance for Kotlin Facet")
                    }
                } catch (e: Exception) {
                    logger.warn("Kargo: Failed to set target platform in Kotlin Facet", e)
                }
            }

            modifiableFacetModel.addFacet(facet)
            modifiableFacetModel.commit()
        } catch (e: Exception) {
            logger.warn("Kargo: Failed to setup Kotlin facet for $moduleName", e)
        }
    }

    private fun resolveAllDependencies(model: Model): Map<Fragment, Set<MavenDependencyNode>> {
        val fragmentToDeps = mutableMapOf<Fragment, MutableSet<MavenDependencyNode>>()
        
        val userCacheRootResult = AmperUserCacheRoot.fromCurrentUserResult()
        val userCacheRoot = if (userCacheRootResult is AmperUserCacheRoot) userCacheRootResult else null
        
        if (userCacheRoot == null) {
            logger.warn("Kargo: Could not initialize Amper user cache root. External dependencies resolution skipped.")
            return emptyMap()
        }

        val resolutionInput = ResolutionInput(
            dependenciesFlowType = DependenciesFlowType.IdeSyncType(model),
            resolutionDepth = ResolutionDepth.GRAPH_FULL,
            resolutionLevel = ResolutionLevel.NETWORK,
            fileCacheBuilder = getAmperFileCacheBuilder(userCacheRoot)
        )

        try {
            runBlocking {
                val rootNode = moduleDependenciesResolver.run { model.modules.resolveDependencies(resolutionInput) }
                
                rootNode.children.filterIsInstance<ModuleDependencyNode>().forEach { moduleNode ->
                    val kargoModule = model.modules.find { it.userReadableName == moduleNode.moduleName }
                    moduleNode.children.filterIsInstance<DirectFragmentDependencyNode>().forEach { fragmentNode ->
                        val fragment = kargoModule?.fragments?.find { it.name == fragmentNode.fragmentName }
                        if (fragment != null) {
                            val deps = fragmentToDeps.getOrPut(fragment) { mutableSetOf() }
                            val bfs = fragmentNode.dependencyNode.distinctBfsSequence().toList()
                            val mavenDeps = bfs.filterIsInstance<MavenDependencyNode>()
                            
                            mavenDeps.forEach { 
                                if (it.dependency.files(false).isNotEmpty()) {
                                    deps.add(it)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Kargo: Failed to resolve external dependencies", e)
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
                            library = projectLibraryModel.createLibrary(libraryName)
                            val libModifiableModel = library.modifiableModel
                            
                            val url = VfsUtilCore.pathToUrl(klibDir.absolutePath)
                            libModifiableModel.addRoot(url, OrderRootType.CLASSES)
                            
                            libModifiableModel.commit()
                            nativeLibMap[libraryName] = library
                        }
                        
                        val existingEntry = modifiableModel.orderEntries.find { it is LibraryOrderEntry && it.libraryName == libraryName }
                        if (existingEntry == null && library != null) {
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
