plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nexora.source.runtime"
}

dependencies {
    implementation(project(":core:logging"))
    implementation(project(":core:network"))
    implementation(project(":source:api"))
    implementation(project(":source:config"))
    testImplementation(libs.kotlin.test.junit)
}
