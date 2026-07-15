plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nexora.core.database"
}

dependencies {
    implementation(project(":core:logging"))
    implementation(project(":core:model"))
    testImplementation(libs.kotlin.test.junit)
}
