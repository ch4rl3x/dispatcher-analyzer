import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "de.charlex"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

val fixtureLibraries = configurations.create("fixtureLibraries")

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) local(localIde.get())
        else androidStudio(providers.gradleProperty("platformVersion").get())
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        pluginVerifier("1.410")
    }
    testImplementation("junit:junit:4.13.2")
    add(fixtureLibraries.name, "org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    add(fixtureLibraries.name, "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
}

kotlin { jvmToolchain(25) }

intellijPlatform {
    pluginConfiguration {
        id = "de.charlex.dispatcher-analyzer"
        name = "Dispatcher Analyzer"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262.9437.185"
            untilBuild = "262.*"
        }
        vendor { name = "charlex" }
    }
    pluginVerification {
        ides { current() }
    }
}

tasks.test {
    useJUnit()
    maxHeapSize = "2g"
    systemProperty("idea.is.unit.test", "true")
    systemProperty("java.awt.headless", "true")
    doFirst {
        systemProperty("dispatcher.fixture.libraries", fixtureLibraries.asPath)
    }
}
