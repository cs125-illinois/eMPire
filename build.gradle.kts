import groovy.util.Node
import java.net.URI
import java.util.Properties

plugins {
    id("java-library")
    kotlin("jvm") version "1.3.41"
    id("com.github.johnrengelman.shadow") version "5.1.0"
    id("signing")
    id("maven-publish")
}

dependencies {
    compileOnly(gradleApi())
    setOf(
            "com.android.tools.build:gradle:3.5.0",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.3.41",
            "com.fasterxml.jackson.core:jackson-databind:2.9.9",
            "com.fasterxml.jackson.module:jackson-module-kotlin:2.9.8",
            "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.9.8",
            "org.ow2.asm:asm:6.0").forEach {
        implementation(it)
        shadow(it)
    }
}

repositories {
    mavenCentral()
    google()
    jcenter()
}

tasks {
    compileKotlin {
        kotlinOptions {
            jvmTarget = "1.8"
        }
    }
}

// The rest of this file is configuration for publishing to Maven Central

val sourceJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets["main"].allSource)
}

artifacts {
    archives(tasks.getByName("jar"))
    archives(sourceJar)
}

signing {
    sign(configurations.getByName("archives")).forEach {
        it.signatures.removeAll { oneSig ->
            it.signatures.count { anotherSig -> oneSig.classifier == anotherSig.classifier } > 1
        }
    }
}

val properties = Properties().also { it.load(rootProject.file("local.properties").inputStream()) }

publishing {
    publications {
        register("mavenJava", MavenPublication::class) {
            pom.withXml {
                asNode().apply {
                    appendNode("description", "MP module manager")
                    appendNode("name", "eMPire")
                    appendNode("url", "https://github.com/cs125-illinois/eMPire")
                    Node(this, "licenses").apply {
                        Node(this, "license").apply {
                            appendNode("name", "Apache License 2.0")
                            appendNode("url", "https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    Node(this, "scm").apply {
                        appendNode("url", "https://github.com/cs125-illinois/eMPire")
                        appendNode("connection", "scm:git:git://github.com/cs125-illinois/eMPire.git")
                        appendNode("developerConnection", "scm:git:ssh://git@github.com:cs125-illinois/eMPire.git")
                    }
                    Node(this, "developers").apply {
                        Node(this, "developer").apply {
                            appendNode("name", "CS 125")
                        }
                    }
                }
            }
            groupId = "com.github.cs125-illinois"
            artifactId = "empire"
            version = "1.0.3"
            from(components.getByName("java"))
            pom.withXml {
                val pomFile = project.file("${project.buildDir}/generated-pom.xml")
                pomFile.writeText(asString().toString().replace("<?xml version=\"1.0\"?>", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
                artifact(signing.sign(pomFile).signatureFiles.singleFile) {
                    classifier = null
                    extension = "pom.asc"
                }
            }
            (tasks.getByName("signArchives") as Sign).signatureFiles.forEach {
                artifact(it) {
                    classifier = if (it.name.replace(".jar.asc", "").endsWith("sources")) "sources" else null
                    extension = "jar.asc"
                }
            }
        }
    }
    repositories {
        maven {
            url = URI("https://oss.sonatype.org/service/local/staging/deploy/maven2")
            credentials {
                username = properties["ossrh.username"] as? String
                password = properties["ossrh.password"] as? String
            }
        }
    }
}

afterEvaluate {
    tasks.filter { it.name.startsWith("publishMavenJavaPublicationTo") }.forEach {
        it.dependsOn("signArchives")
    }
}
