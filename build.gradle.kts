import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate(providers.gradleProperty("platformVersion").get())
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.tweety.reviewqueue"
        name = "Review Queue"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main { kotlin.srcDir("src/main/kotlin") }
    test { kotlin.srcDir("src/test/kotlin") }
}
