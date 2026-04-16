package com.example.distribution


import org.jetbrains.amper.plugins.*
import kotlin.concurrent.thread
import kotlin.io.path.*
import java.nio.file.Path

@Configurable
interface DistributionSettings {
    val extraNamedClasspaths: Map<String, Classpath>
    val extraDependency: Dependency?
    val someInt: Int
}

@TaskAction
fun buildDistribution(
    someInt: Int,
    someInt2: Int,
    @Output distributionDir: Path,
    @Input baseJar: CompilationArtifact,
    @Input baseClasses: CompilationArtifact,
    @Input baseClasspath: Classpath,
    @Input settings: DistributionSettings,
    @Input localProperties: Path,
) {
    distributionDir.createDirectories()
    println("Hello from distribution")
    println("someInt: $someInt, someInt2: $someInt2")
    println("local.properties: ${localProperties}")
    printClasspathInfo("base", baseClasspath)
    settings.extraNamedClasspaths.forEach { (name, classpath) ->
        printClasspathInfo(name, classpath)
    }
    println("classes result: ${baseClasses}")
    baseClasses.artifact.walk().map { it.toString() }.sorted().forEachIndexed { i, path ->
        println("classes result contents[$i] = $path")
    }
    val t = thread {
        println("compilation result: ${baseJar}")
        val t2 = thread {
            println("compilation result path: ${baseJar.artifact}")
        }
        t2.join()
    }
    t.join()
}

private fun printClasspathInfo(name: String, classpath: Classpath) {
    println("classpath $name.dependencies = ${classpath.dependencies}")
    classpath.dependencies.forEachIndexed { index, it ->
        println("classpath $name.dependencies[$index] = ${it}")
        when(it) {
            is Dependency.Maven -> println("classpath $name.dependencies[$index].coordinates = ${it.coordinates}")
            is Dependency.Local -> println("classpath $name.dependencies[$index].modulePath = ${it.modulePath}")
        }
    }
    println("classpath $name.resolvedFiles = ${classpath.resolvedFiles}")
}