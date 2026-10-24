plugins {
    alias(libs.plugins.android.library)
}

/*
 * THE `tracking` EXTENSION (README.md): the position shared while a task is under way, from the background too, after
 * a disclosure. An app turns it on with `TrackingExtension(TrackingConfig(...))` in its Shell.
 */
group = "com.vougadev"

android {
    namespace = "com.vougadev.shell.tracking"
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
