plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nexora.player.media3"
}

dependencies {
    implementation(project(":core:network"))
    implementation(project(":core:logging"))
    implementation(project(":player:api"))
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
