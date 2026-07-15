plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit)
}
