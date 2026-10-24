plugins {
    alias(libs.plugins.android.library)
}

/*
 * THE `offline-map` EXTENSION (README.md): map regions kept on the phone, answering the site's map pages and the app's
 * native maps with no network. An app turns it on with `OfflineMapExtension(OfflineMapConfig(...))` in its Shell.
 */
group = "com.vougadev"

android {
    namespace = "com.vougadev.shell.offlinemap"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":shell"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
