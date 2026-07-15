plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nexora.player.media3"
}

dependencies {
    implementation(project(":core:logging"))
    implementation(project(":player:api"))
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
}
