import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

group = "com.nexora"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version

    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationExtension> {
            compileSdk = 36
            defaultConfig {
                minSdk = 26
                targetSdk = 36
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            lint {
                abortOnError = true
                checkReleaseBuilds = true
                warningsAsErrors = true
            }
        }
    }

    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = 36
            defaultConfig {
                minSdk = 26
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            lint {
                abortOnError = true
                checkReleaseBuilds = true
                warningsAsErrors = true
            }
        }
    }
}

val allowedModuleEdges = mapOf(
    ":app-mobile" to setOf(":core:designsystem", ":feature:home"),
    ":app-tv" to setOf(":core:designsystem"),
    ":core:common" to emptySet(),
    ":core:database" to setOf(":core:logging", ":core:model"),
    ":core:designsystem" to setOf(":core:common"),
    ":core:logging" to setOf(":core:common"),
    ":core:model" to setOf(":core:common"),
    ":core:network" to setOf(":core:common", ":core:logging"),
    ":feature:home" to setOf(":core:designsystem", ":core:model"),
    ":feature:library" to setOf(":core:designsystem", ":core:model", ":storage:api"),
    ":feature:player" to setOf(":core:designsystem", ":core:model", ":player:api"),
    ":feature:settings" to setOf(":core:designsystem", ":storage:api"),
    ":feature:sources" to setOf(":core:designsystem", ":source:api"),
    ":player:api" to setOf(":core:common", ":core:model"),
    ":player:media3" to setOf(":core:logging", ":player:api"),
    ":source:api" to setOf(":core:common", ":core:model"),
    ":source:config" to setOf(":core:common", ":source:api"),
    ":source:runtime" to setOf(":core:logging", ":core:network", ":source:api", ":source:config"),
    ":source:testkit" to setOf(":source:api", ":source:config"),
    ":storage:api" to setOf(":core:common", ":core:model"),
    ":storage:local" to setOf(":core:logging", ":storage:api"),
    ":storage:nfs" to setOf(":core:logging", ":core:network", ":storage:api"),
    ":storage:smb" to setOf(":core:logging", ":core:network", ":storage:api"),
    ":storage:webdav" to setOf(":core:logging", ":core:network", ":storage:api"),
)

tasks.register("checkModuleDependencies") {
    group = "verification"
    description = "Verifies the allowed Nexora module edges and rejects dependency cycles."

    doLast {
        val configuredModules = subprojects.filter { module -> module.buildFile.isFile }
        val graph = configuredModules.associate { module ->
            val dependencies = module.configurations
                .flatMap { configuration ->
                    configuration.dependencies
                        .withType(ProjectDependency::class.java)
                        .map(ProjectDependency::getPath)
                        .filterNot(module.path::equals)
                }
                .toSet()
            module.path to dependencies
        }

        val unknownModules = graph.keys - allowedModuleEdges.keys
        check(unknownModules.isEmpty()) {
            "Missing dependency policy for modules: ${unknownModules.sorted().joinToString()}"
        }

        graph.forEach { (module, dependencies) ->
            val forbidden = dependencies - allowedModuleEdges.getValue(module)
            check(forbidden.isEmpty()) {
                "$module has forbidden project dependencies: ${forbidden.sorted().joinToString()}"
            }
        }

        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(module: String, path: List<String>) {
            check(module !in visiting) {
                "Module dependency cycle: ${(path + module).joinToString(" -> ")}"
            }
            if (module in visited) return

            visiting += module
            graph.getValue(module).forEach { dependency -> visit(dependency, path + module) }
            visiting -= module
            visited += module
        }

        graph.keys.sorted().forEach { module -> visit(module, emptyList()) }
        logger.lifecycle("Verified ${graph.size} modules: allowed edges only, no cycles.")
    }
}
