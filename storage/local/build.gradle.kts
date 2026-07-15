plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nexora.storage.local"
}

dependencies {
    implementation(project(":core:logging"))
    implementation(project(":storage:api"))
}
