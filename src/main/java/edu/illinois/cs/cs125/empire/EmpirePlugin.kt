package edu.illinois.cs.cs125.empire

import com.android.build.gradle.tasks.ManifestProcessorTask
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.DependencyResolutionListener
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.tasks.compile.JavaCompile
import org.objectweb.asm.*
import org.objectweb.asm.commons.AdviceAdapter
import org.w3c.dom.Document
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * The eMPire Gradle plugin.
 */
@Suppress("unused")
class EmpirePlugin : Plugin<Project> {

    /** The project the plugin was applied to. */
    private lateinit var project: Project

    /** Configuration from the Gradle script. */
    private lateinit var gradleConfig: EmpireExtension

    /** Collection of segments that need to be swapped out. */
    private lateinit var replacedSegments: Iterable<EmpireSegment>

    /** Opportunistic compile tasks that have been created. */
    private lateinit var opportunisticCompileTasks: Map<String, JavaCompile>

    /** The checkpoint the student is working on, if any. */
    private var checkpoint: EmpireCheckpoint? = null

    /**
     * Applies the plugin to a Gradle project.
     * @param project the project the plugin is being applied to
     */
    override fun apply(project: Project) {
        // Set up extension
        this.project = project
        gradleConfig = project.extensions.create("eMPire", EmpireExtension::class.java)
        gradleConfig.studentConfig = project.file("config/eMPire.yaml")
        gradleConfig.checkpoints = project.container(EmpireCheckpoint::class.java).also { it.add(EmpireCheckpoint("none")) }
        gradleConfig.segments = project.container(EmpireSegment::class.java)

        // Set up AAR repository
        project.repositories.flatDir { it.dirs("provided") }

        // Set up the dependency listener
        project.gradle.addListener(object : DependencyResolutionListener {
            override fun beforeResolve(deps: ResolvableDependencies) {
                if (gradleConfig.segments.size == 0) return
                adjustDependencies()
                project.gradle.removeListener(this)
            }
            override fun afterResolve(unused: ResolvableDependencies) { }
        })

        // Set up compilation hooks
        project.tasks.withType(JavaCompile::class.java) hookJavac@{ javaTask ->
            if (gradleConfig.studentCompileTasks?.contains(javaTask.name) != false) {
                applyConfigDependency(javaTask)
                javaTask.doFirst("eMPire adjustCompile") {
                    adjustCompile(javaTask)
                }
                javaTask.doLast("eMPire postprocessCompile") {
                    postprocessCompile(javaTask)
                }
            }
            if (gradleConfig.opportunisticCompile.javacTasks?.contains(javaTask.name) == true) {
                val reconfTask = project.task("configForOpportunistic${javaTask.name.capitalize()}")
                reconfTask.doLast {
                    gradleConfig.opportunisticCompile.classes?.forEach { file ->
                        javaTask.excludes.add("${gradleConfig.excludedSrcPath}/$file.*")
                    }
                    opportunisticCompileTasks.forEach { (name, task) ->
                        task.options.sourcepath = project.fileTree(gradleConfig.opportunisticCompile.javaRoot!!)
                        task.source = task.options.sourcepath!!.asFileTree
                        task.include("${gradleConfig.excludedSrcPath}/$name.*")
                        task.classpath = javaTask.classpath + javaTask.outputs.files
                        task.options.bootstrapClasspath = javaTask.options.bootstrapClasspath
                        task.destinationDir = javaTask.destinationDir
                        task.sourceCompatibility = javaTask.sourceCompatibility
                        task.targetCompatibility = javaTask.targetCompatibility
                    }
                }
                javaTask.dependsOn(reconfTask)
                javaTask.doLast("eMPire cleanStaleOpportunisticClasses") {
                    cleanStaleOpportunisticCompile(javaTask)
                }
            }
        }

        // Finish setup when project is evaluated
        project.afterEvaluate {
            // Create opportunistic compile tasks
            project.tasks.matching { it.name in (gradleConfig.opportunisticCompile.dependentTasks ?: mutableSetOf()) }.all {
                if (!this::opportunisticCompileTasks.isInitialized) {
                    loadConfig()
                    opportunisticCompileTasks = gradleConfig.opportunisticCompile.classes!!.filter { f ->
                        checkpoint?.opportunisticCompileClasses?.contains(f) != false
                    }.map { f ->
                        val gradleSafeName = f.capitalize().replace("/", "")
                        val newJavac = project.tasks.register("tryCompile$gradleSafeName", JavaCompile::class.java).get()
                        newJavac.mustRunAfter(*gradleConfig.opportunisticCompile.javacTasks!!.toTypedArray())
                        newJavac.options.isIncremental = false
                        newJavac.options.isFailOnError = false
                        f to newJavac
                    }.toMap()
                }
                opportunisticCompileTasks.values.forEach { oc -> it.dependsOn(oc) }
            }

            // Hook manifest processor tasks
            val xmlLoader = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val xmlWriter = TransformerFactory.newInstance().newTransformer()
            val editors by lazy {
                replacedSegments.flatMap { it.manifestEditors }.map {
                    val loader = URLClassLoader(arrayOf(project.file("provided/${it.file}").toURI().toURL()), javaClass.classLoader)
                    val method = loader.loadClass(it.className).getMethod(it.method, Document::class.java)
                    Action<Document> { doc -> method.invoke(null, doc) }
                }
            }
            project.tasks.withType(ManifestProcessorTask::class.java) { processManifest ->
                loadConfig()
                applyConfigDependency(processManifest)
                if (editors.isEmpty()) return@withType
                processManifest.doLast("eMPire manifestEditor") {
                    setOf(processManifest.manifestOutputDirectory).mapNotNull { dp -> dp.orNull?.asFile }.forEach eachDir@{ d ->
                        val manifestFile = d.listFiles { f -> f.name == "AndroidManifest.xml" }?.firstOrNull() ?: return@eachDir
                        val manifestXml = xmlLoader.parse(manifestFile)
                        editors.forEach { edit -> edit.execute(manifestXml) }
                        xmlWriter.transform(DOMSource(manifestXml), StreamResult(manifestFile))
                    }
                }
            }
        }
    }

    /**
     * Makes sure all necessary configuration is loaded.
     */
    private fun loadConfig() {
        if (this::replacedSegments.isInitialized) return
        replacedSegments = if (project.hasProperty("empire.replace")) {
            val toReplace = (project.property("empire.replace") as String).split(',')
            gradleConfig.segments.filter { toReplace.contains(it.name) }
        } else if (project.hasProperty("empire.checkpoint")) {
            checkpoint = gradleConfig.checkpoints.getByName(project.property("empire.checkpoint") as String)
            checkpoint!!.segments.map { gradleConfig.segments.getByName(it) }
        } else {
            val loader = ObjectMapper(YAMLFactory()).also { it.registerModule(KotlinModule()) }
            val studentConfig = loader.readValue(Files.newBufferedReader(gradleConfig.studentConfig!!.toPath()),
                    EmpireStudentConfig::class.java)
            if (studentConfig.checkpoint != null) {
                checkpoint = gradleConfig.checkpoints.getByName(studentConfig.checkpoint)
            }
            if (!studentConfig.useProvided) {
                listOf()
            } else if (studentConfig.segments != null) {
                gradleConfig.segments.filter { studentConfig.segments[it.name] ?: false }
            } else if (studentConfig.checkpoint != null) {
                checkpoint!!.segments.map { gradleConfig.segments.getByName(it) }
            } else {
                listOf()
            }
        }
    }

    /**
     * Makes a task depend on the configured set of replaced segments.
     * @param task the task that needs to be redone if the eMPire setting changes
     */
    private fun applyConfigDependency(task: Task) {
        task.inputs.property("segments", Callable { replacedSegments.joinToString { it.name } })
    }

    /**
     * Adjusts dependencies as directed.
     */
    private fun adjustDependencies() {
        loadConfig()
        val dependencies = project.configurations.getByName("implementation").dependencies
        replacedSegments.flatMap { seg -> seg.addJars }.forEach {
            dependencies.add(project.dependencies.create(project.files("provided/$it.jar")))
        }
        replacedSegments.flatMap { seg -> seg.addAars }.forEach {
            dependencies.add(project.dependencies.create(mapOf("name" to it, "ext" to "aar")))
        }
    }

    /**
     * Deletes class files corresponding to opportunistically compiled files not needed in this checkpoint.
     * @param task the compilation task to clean outputs of
     */
    private fun cleanStaleOpportunisticCompile(task: JavaCompile) {
        val ocLimit = checkpoint?.opportunisticCompileClasses ?: return
        val ocAll = gradleConfig.opportunisticCompile.classes ?: return
        val stale = ocAll - ocLimit
        task.destinationDir.walkTopDown()
                .filter { isAffectedClassFile(it) && getRelativeName(it).split('$')[0] in stale }
                .forEach { it.delete() }
    }

    /**
     * Adjusts a Java compilation task to exclude appropriate files.
     * @param task the compilation task to adjust sources for
     */
    private fun adjustCompile(task: JavaCompile) {
        loadConfig()
        replacedSegments.flatMap { seg -> seg.removeClasses }.forEach {
            task.excludes.add("${gradleConfig.excludedSrcPath}/$it.*")
        }
    }

    /**
     * Postprocesses the result of a student Java compilation task.
     * @param task the compilation task that just completed
     */
    private fun postprocessCompile(task: JavaCompile) {
        redactCompile(task)
        injectClassesAfterCompile(task)
        chimerizeAfterCompile(task)
    }

    /**
     * Deletes undesired outputs of a Java compilation task.
     * Otherwise a full clean would be needed after the student configuration changed.
     * @param task the compilation task that just completed
     */
    private fun redactCompile(task: JavaCompile) {
        val toDelete = replacedSegments.flatMap { seg -> seg.removeClasses }
        task.destinationDir.walkTopDown()
                .filter { isAffectedClassFile(it) && (getRelativeName(it).split("\\$")[0] in toDelete) }
                .forEach { it.delete() }
    }

    /**
     * Applies injectors to compiled student classes.
     * @param task the compilation task that just completed
     */
    private fun injectClassesAfterCompile(task: JavaCompile) {
        val toApply = replacedSegments.flatMap { it.injectors }
        if (toApply.isEmpty()) return
        task.destinationDir.walkTopDown().filter { isAffectedClassFile(it) }.forEach {
            val classInjectors = toApply.filter { i -> i.targetClass == getRelativeName(it) }
            if (classInjectors.isEmpty()) return@forEach
            val classReader = ClassReader(it.readBytes())
            val classWriter = ClassWriter(classReader, ClassWriter.COMPUTE_FRAMES)
            val classVisitor = object : ClassVisitor(Opcodes.ASM6, classWriter) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                    val injector = classInjectors.firstOrNull { i -> i.targetMethod == name } ?: return mv
                    return object : AdviceAdapter(Opcodes.ASM6, mv, access, name, descriptor) {
                        override fun onMethodExit(opcode: Int) {
                            if (opcode == Opcodes.ATHROW) return
                            mv.visitVarInsn(Opcodes.ALOAD, 0)
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, injector.injectorClass, injector.injectorMethod,
                                    "(Ljava/lang/Object;)V", false)
                        }
                    }
                }
            }
            classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)
            it.writeBytes(classWriter.toByteArray())
        }
    }

    /**
     * Applies chimerization to the results of a student compilation task.
     * @param task the Java compilation task that just finished
     */
    private fun chimerizeAfterCompile(task: JavaCompile) {
        val toChimerize = replacedSegments.flatMap { it.chimeras }
        if (toChimerize.isEmpty()) return
        val targetFiles = mutableMapOf<String, File>()
        task.destinationDir.walkTopDown().filter { isAffectedClassFile(it) }.forEach {
            val className = getRelativeName(it).split('$', limit = 2)[0]
            if (className !in toChimerize.map { c -> c.targetClass }) return@forEach
            if (getRelativeName(it).contains('$')) {
                it.delete()
            } else {
                targetFiles[className] = it
            }
        }
        toChimerize.forEach { chimera ->
            val originalClass = targetFiles[chimera.targetClass]!!.readBytes()
            @Suppress("UNCHECKED_CAST") val camoFields = chimera.camo?.let {
                val loader = URLClassLoader(arrayOf(project.file("provided/${it.file}").toURI().toURL()), javaClass.classLoader)
                val method = loader.loadClass(it.className).getMethod(it.method, ByteArray::class.java)
                method.invoke(null, originalClass) as Map<String, String>
            } ?: mapOf()
            val jar = JarFile(project.file("provided/${chimera.jar}"))
            jar.stream().filter { !it.isDirectory && it.name.endsWith(".class") }.forEach { entry ->
                val destination = File(task.destinationDir, entry.name)
                destination.parentFile.mkdirs()
                val entryStream = jar.getInputStream(entry)
                destination.writeBytes(entryStream.readBytes())
                entryStream.close()
            }
            jar.close()
            val originalReader = ClassReader(originalClass)
            val providedReader = ClassReader(targetFiles[chimera.targetClass]!!.readBytes())
            val writer = ClassWriter(0)
            val addedFields = mutableListOf<String>()
            val addedMethods = mutableListOf<String>()
            val filteringVisitor = object : ClassVisitor(Opcodes.ASM6) {
                override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    return if (name == chimera.replaceMethod || "$name$descriptor".toLowerCase() !in addedMethods) {
                        writer.visitMethod(access, name, descriptor, signature, exceptions)
                    } else {
                        null
                    }
                }
                override fun visitField(access: Int, name: String?, descriptor: String?, signature: String?, value: Any?): FieldVisitor? {
                    return if (name in addedFields) {
                        null
                    } else {
                        writer.visitField(access, name, descriptor, signature, value)
                    }
                }
            }
            val chimeraVisitor = object : ClassVisitor(Opcodes.ASM6, writer) {
                override fun visitMethod(access: Int, name: String, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    return if (name == chimera.replaceMethod) {
                        null
                    } else {
                        addedMethods.add("$name$descriptor".toLowerCase())
                        super.visitMethod(access, name, descriptor, signature, exceptions)
                    }
                }
                override fun visitField(access: Int, name: String, descriptor: String?, signature: String?, value: Any?): FieldVisitor {
                    addedFields.add(name)
                    return super.visitField(access, name, descriptor, signature, value)
                }
                override fun visitEnd() {
                    visitField(Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_PRIVATE,
                            "_EMPIRE_CHIMERA", Type.getDescriptor(String::class.java), null,
                            "This chimeric class comes from provided code combined with your ${chimera.replaceMethod} method.").visitEnd()
                    camoFields.forEach { (name, value) ->
                        visitField(Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_PRIVATE,
                                name, Type.getDescriptor(String::class.java), null, value).visitEnd()
                    }
                    originalReader.accept(filteringVisitor, 0)
                    super.visitEnd()
                }
            }
            providedReader.accept(chimeraVisitor, 0)
            targetFiles[chimera.targetClass]!!.writeBytes(writer.toByteArray())
        }
    }

    /**
     * Determines whether a file is a student class file.
     * @param file the file
     * @return whether it's a Java class file that may need to be adjusted
     */
    private fun isAffectedClassFile(file: File): Boolean {
        if (!file.isFile || file.extension.toLowerCase() != "class") return false
        return file.parentFile.absolutePath.replace('\\', '/')
                .substring(project.projectDir.absolutePath.length).contains(gradleConfig.excludedSrcPath)
    }

    /**
     * Gets the name (no extension) of a file relative to the excluded sources path.
     * @param file the file
     * @return the path component after the excluded sources path, with separators standardized to /
     */
    private fun getRelativeName(file: File): String {
        return file.absolutePath.replace('\\', '/')
                .substringAfterLast(gradleConfig.excludedSrcPath).trimStart('/').substringBeforeLast('.')
    }

}