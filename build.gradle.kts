import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.kotlin.js.dce.InputResource.Companion.file

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.gradleIntelliJPlugin) // Gradle IntelliJ Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

// Configure project's dependencies
repositories {
    mavenCentral()
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
//    implementation(libs.annotations)
}

// Set the JVM language level used to build the project. Use Java 11 for 2020.3+, and Java 17 for 2022.2+.
kotlin {
    jvmToolchain(17)
}

// Configure Gradle IntelliJ Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    pluginName = properties("pluginName")
    version = properties("platformVersion")
    type = properties("platformType")

    // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file.
    plugins = properties("platformPlugins").map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = properties("pluginRepositoryUrl")
}

// Configure Gradle Qodana Plugin - read more: https://github.com/JetBrains/gradle-qodana-plugin
qodana {
    cachePath = provider { file(".qodana").canonicalPath }
    reportPath = provider { file("build/reports/inspections").canonicalPath }
    saveReport = true
    showReport = environment("QODANA_SHOW_REPORT").map { it.toBoolean() }.getOrElse(false)
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
koverReport {
    defaults {
        xml {
            onCheck = true
        }
    }
}

tasks {
    wrapper {
        gradleVersion = properties("gradleVersion").get()
    }

    patchPluginXml {
        version = properties("pluginVersion")
        sinceBuild = properties("pluginSinceBuild")
        untilBuild = properties("pluginUntilBuild")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        pluginDescription = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with (it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = properties("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

    // Configure UI tests plugin
    // Read more: https://github.com/JetBrains/intellij-ui-test-robot
    runIdeForUiTests {
        systemProperty("robot-server.port", "8082")
        systemProperty("ide.mac.message.dialogs.as.sheets", "false")
        systemProperty("jb.privacy.policy.text", "<!--999.999-->")
        systemProperty("jb.consents.confirmation.enabled", "false")
    }

    signPlugin {
        certificateChain = environment("CERTIFICATE_CHAIN")
        privateKey = environment("PRIVATE_KEY")
        password = environment("PRIVATE_KEY_PASSWORD")
    }

    publishPlugin {
        dependsOn("patchChangelog")
        token = environment("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = properties("pluginVersion").map { listOf(it.split('-').getOrElse(1) { "default" }.split('.').first()) }
    }
}

abstract class InstallPlugin : DefaultTask() {
    @get:Internal
    var pluginName: String? = null

    @get:Internal
    var projectVersion: Any = ""
}

tasks.register<InstallPlugin>("installPlugin") {
    // disable cache
    outputs.upToDateWhen { false }

    val pluginNameLocal = properties("pluginName").get()
    val projectVersionLocal = project.version

    pluginName = pluginNameLocal
    projectVersion = projectVersionLocal

    doLast {
        val envFile = "../.env"

        // Load environment file if it exists
        val envFileObj = file(envFile)
        var envMap = mutableMapOf<String, String>()
        if (envFileObj.exists()) {
            envFileObj.readLines().forEach {
                if (it.isNotEmpty() && !it.startsWith("#")) {
                    val pos = it.indexOf("=")
                    val key = it.substring(0, pos)
                    val value = it.substring(pos + 1)
                    // check if the key is already set
                    if (environment(key).getOrNull() == null) {
                        envMap[key] = value
                    }
                }
            }
        }

        val installLocationsCopy = envMap["INSTALL_LOCATIONS"] ?: environment("INSTALL_LOCATIONS").getOrNull()
        if (installLocationsCopy == null) {
            println("[WARNING] No install locations specified")
            return@doLast
        }

        val locationsList: List<String> = installLocationsCopy.split(",")

        // eg. build/distributions/Plugin-2000.10.1.100.zip
        val pluginZip: File = file("build/distributions/${pluginName}-$projectVersion.zip")

        locationsList.forEach { location ->
            // extract installation name (eg  C:\\Users\\...\\JetBrains\\Rider2023.2\\plugins
            // -> Rider2023.2)
            val separator = if (location.contains("/")) "/" else "\\"
            val installationName = location.split(separator).dropLast(1).last()

            // delete plugin folder
            val existingInstallation = file("$location/$pluginName")
            if (existingInstallation.exists()) {
                if (!existingInstallation.deleteRecursively()) {
                    println("[ERROR] Skipping $installationName. Failed to delete existing installation")
                    return@forEach
                }
            }

            copy {
                from(zipTree(pluginZip))
                into(location)
            }

            println("Plugin installed to $installationName")
        }
    }
}
