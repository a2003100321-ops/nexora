plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nexora.storage.smb"
}

dependencies {
    implementation(project(":core:logging"))
    implementation(project(":core:network"))
    implementation(project(":storage:api"))
}
