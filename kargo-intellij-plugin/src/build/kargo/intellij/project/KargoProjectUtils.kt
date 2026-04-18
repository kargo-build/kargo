package build.kargo.intellij.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

private val KARGO_CONFIG_FILES = setOf("project.yaml", "module.yaml")

/** Returns true if [file] is a Kargo configuration file (`project.yaml` or `module.yaml`). */
fun isKargoFile(file: VirtualFile): Boolean = file.name in KARGO_CONFIG_FILES

/** Returns true if [dir] is a Kargo project/module directory (contains a config file). */
fun isKargoDir(dir: VirtualFile): Boolean = KARGO_CONFIG_FILES.any { dir.findChild(it) != null }

/**
 * Returns true if [project] is a Kargo project,
 * i.e., its base directory contains a `project.yaml` or `module.yaml` file.
 */
fun isKargoProject(project: Project): Boolean {
    val basePath = project.basePath ?: return false
    val baseDir = File(basePath)
    return KARGO_CONFIG_FILES.any { baseDir.resolve(it).exists() }
}
