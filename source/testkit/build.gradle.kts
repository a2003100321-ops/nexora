plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":source:api"))
    implementation(project(":source:config"))

    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnit()
    systemProperty(
        "nexora.legacyConfigCorpus",
        rootProject.layout.projectDirectory.dir("test-corpus/legacy-config").asFile.absolutePath,
    )
}
