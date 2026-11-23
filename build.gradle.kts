import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "io.github.pingecc"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// By default the plugin is compiled against IntelliJ IDEA Community 2024.2.6.
// In an offline environment you can point the build at a local IDE installation:
//   ./gradlew compileKotlin -PlocalIdePath="C:\path\to\IntelliJ IDEA 2024.2.6"
val localIdePath: String? = providers.gradleProperty("localIdePath").orNull

dependencies {
    intellijPlatform {
        if (localIdePath != null) {
            local(localIdePath)
        } else {
            intellijIdeaCommunity("2024.2.6")
        }
        bundledPlugin("Git4Idea")
        bundledPlugin("com.intellij.java")
    }
}

// IntelliJ Platform 2024.2+ is compiled for Java 21.
val javaToolchain: Int = providers.gradleProperty("javaToolchain").map(String::toInt).orElse(21).get()

kotlin {
    jvmToolchain(javaToolchain)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // IDEA 2024.2 bundles Kotlin stdlib 2.0.x; keep the plugin API level at 2.0
        // so it never links against newer stdlib members than the target IDE has.
        languageVersion = KotlinVersion.KOTLIN_2_0
        apiVersion = KotlinVersion.KOTLIN_2_0
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaToolchain)
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        id = "io.github.pingecc.reviewatlas"
        name = "ReviewAtlas"
        version = project.version.toString()

        description = """
            <p><b>ReviewAtlas</b> lets you review an arbitrary historical Git commit
            (or the current uncommitted changes) in a dedicated side-panel ToolWindow.</p>
            <ul>
              <li>Asynchronously loads the latest 30 commits, with refresh.</li>
              <li>Renders changed files as a compact directory tree, collapsing single-child folders.</li>
              <li>Colors and icons distinguish added / modified / deleted / moved files.</li>
              <li>Double-click opens the native IntelliJ diff; the context menu can jump to source.</li>
            </ul>
        """.trimIndent()

        vendor {
            name = "pingecc"
            url = "https://github.com/pingecc"
        }

        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}