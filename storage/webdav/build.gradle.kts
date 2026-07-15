plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nexora.storage.webdav"
}

dependencies {
    implementation(project(":core:logging"))
    implementation(project(":core:network"))
    implementation(project(":storage:api"))
}
