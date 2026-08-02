plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nexora.tv"

    buildFeatures {
        compose = true
    }

    defaultConfig {
        applicationId = "com.nexora.tv"
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    val releaseKeystoreFile = providers.environmentVariable("NEXORA_KEYSTORE_FILE")
    val releaseKeystorePassword = providers.environmentVariable("NEXORA_KEYSTORE_PASSWORD")
    val releaseKeyAlias = providers.environmentVariable("NEXORA_KEY_ALIAS")
    val releaseKeyPassword = providers.environmentVariable("NEXORA_KEY_PASSWORD")
    val releaseSigningAvailable = listOf(
        releaseKeystoreFile,
        releaseKeystorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { value -> value.isPresent }

    if (releaseSigningAvailable) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystoreFile.get())
                storePassword = releaseKeystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
}
