plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nexora.source.sandbox"
    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation(project(":source:plugin-api"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
