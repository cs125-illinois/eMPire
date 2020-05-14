@file:Suppress("unused")

package edu.illinois.cs.cs125.empire

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import java.io.File

open class EmpireExtension {
    var excludedSrcPath: String = "**"
    var allowArbitrarySegmentCombination: Boolean = false
    lateinit var checkpoints: NamedDomainObjectContainer<EmpireCheckpoint>
    var opportunisticCompile: EmpireOpportunisticCompiler = EmpireOpportunisticCompiler()
    lateinit var segments: NamedDomainObjectContainer<EmpireSegment>
    var studentCompileTasks: MutableSet<String>? = null
    var studentConfig: File? = null

    fun allowArbitrarySegmentCombination() {
        allowArbitrarySegmentCombination = true
    }
    fun checkpoints(action: Closure<NamedDomainObjectContainer<EmpireCheckpoint>>) {
        checkpoints.configure(action)
    }
    fun opportunisticCompile(action: Action<EmpireOpportunisticCompiler>) {
        action.execute(opportunisticCompile)
    }
    fun opportunisticCompile(action: Closure<EmpireOpportunisticCompiler>) {
        action.also { it.delegate = opportunisticCompile }.call()
    }
    fun segments(action: Closure<NamedDomainObjectContainer<EmpireSegment>>) {
        segments.configure(action)
    }
    fun studentCompileTasks(vararg tasks: String) {
        if (studentCompileTasks == null) studentCompileTasks = mutableSetOf()
        studentCompileTasks!!.addAll(tasks)
    }
}

open class EmpireCheckpoint(val name: String) {
    var opportunisticCompileClasses: Set<String>? = null
    var segments = mutableSetOf<String>()

    fun limitOpportunisticCompile(vararg classes: String) {
        opportunisticCompileClasses = classes.toSet()
    }
    fun segments(vararg toAdd: String) {
        segments.addAll(toAdd)
    }
}

open class EmpireOpportunisticCompiler {
    var classes: MutableSet<String>? = null
    var javacTasks: MutableSet<String>? = null
    var javaRoot: File? = null
    var dependentTasks: MutableSet<String>? = null

    fun classes(vararg toAdd: String) {
        if (classes == null) classes = mutableSetOf()
        classes!!.addAll(toAdd)
    }
    fun javacTasks(vararg toAdd: String) {
        if (javacTasks == null) javacTasks = mutableSetOf()
        javacTasks!!.addAll(toAdd)
    }
    fun dependentTasks(vararg toAdd: String) {
        if (dependentTasks == null) dependentTasks = mutableSetOf()
        dependentTasks!!.addAll(toAdd)
    }
}

open class EmpireSegment(val name: String) {
    internal data class ChimeraConfig(val jar: String, val targetClass: String, val replaceMethod: String, val camo: ChimeraCamouflageConfig?)
    internal data class ChimeraCamouflageConfig(val file: String, val className: String, val method: String)
    internal val chimeras = mutableListOf<ChimeraConfig>()
    internal data class InjectorConfig(val targetClass: String, val targetMethod: String, val injectorClass: String, val injectorMethod: String)
    internal val injectors = mutableListOf<InjectorConfig>()
    internal data class ManifestEditorConfig(val file: String, val className: String, val method: String)
    internal val manifestEditors = mutableListOf<ManifestEditorConfig>()

    var addAars: MutableSet<String> = mutableSetOf()
    var addJars: MutableSet<String> = mutableSetOf()
    var removeClasses: MutableSet<String> = mutableSetOf()

    fun addAars(vararg aars: String) {
        addAars.addAll(aars)
    }
    fun addJars(vararg jars: String) {
        addJars.addAll(jars)
    }
    fun chimera(jar: String, targetClass: String, replaceMethod: String) {
        chimeras.add(ChimeraConfig(jar, targetClass, replaceMethod, null))
    }
    fun chimera(jar: String, targetClass: String, replaceMethod: String, camoFile: String, camoClass: String, camoMethod: String) {
        chimeras.add(ChimeraConfig(jar, targetClass, replaceMethod, ChimeraCamouflageConfig(camoFile, camoClass, camoMethod)))
    }
    @JvmOverloads
    fun injector(targetClass: String, targetMethod: String, injectorClass: String, injectorMethod: String = "inject") {
        injectors.add(InjectorConfig(targetClass, targetMethod, injectorClass, injectorMethod))
    }
    @JvmOverloads
    fun manifestEditor(file: String, className: String, method: String = "editManifest") {
        manifestEditors.add(ManifestEditorConfig(file, className, method))
    }
    fun removeClasses(vararg classes: String) {
        removeClasses.addAll(classes)
    }
}
