@file:Suppress("UnstableApiUsage")

pluginManagement {
    val kotlinVersion: String by settings
    val shadowJarVersion: String by settings
    val ktlintVersion: String by settings

    repositories {
        jcenter()
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        // only when using Kotlin EAP releases ...
        maven { url = uri("https://dl.bintray.com/kotlin/kotlin-eap") }
        maven { url = uri("https://dl.bintray.com/kotlin/kotlin-dev") }

        // Used for TeamCity build
        val m2LocalPath = File(".m2/repository")
        if (m2LocalPath.exists()) {
            maven(m2LocalPath.toURI())
        }
    }

    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.github.johnrengelman.shadow" -> useModule("com.github.jengelman.gradle.plugins:shadow:$shadowJarVersion")
                "org.jlleitschuh.gradle.ktlint" -> useModule("org.jlleitschuh.gradle:ktlint-gradle:$ktlintVersion")
            }
        }
    }

    plugins {
        kotlin("jvm") version kotlinVersion
        id("com.github.johnrengelman.shadow") version shadowJarVersion
        id("org.jlleitschuh.gradle.ktlint") version ktlintVersion
        id("org.jetbrains.kotlin.jupyter.dependencies")
    }
}

gradle.projectsLoaded {
    allprojects {
        repositories.addAll(pluginManagement.repositories)
    }
}

val pluginProject = "kotlin-jupyter-plugin"
val depsProject = "kotlin-jupyter-deps"
val apiProject = "kotlin-jupyter-api"
val libProject = "jupyter-lib"

includeBuild(pluginProject)
include(depsProject)
project(":$depsProject").projectDir = file("$pluginProject/$depsProject")

include(libProject)
include(apiProject)
project(":$apiProject").projectDir = file("$libProject/$apiProject")
