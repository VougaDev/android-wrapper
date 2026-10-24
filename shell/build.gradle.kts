plugins {
    alias(libs.plugins.android.library)
}

/*
 * VOUGADEV NATIVE, THE SHELL every VougaDev app builds on (see README.md): the WebView host, the
 * sign-in with the account service in the system browser, the session the WebView and native calls share, and window.VougaDevNative.
 * Nothing in it is one app's: an app gives its ShellConfig, its colours and texts (resources named `shell_*`), and the
 * bridge members and native screens of its own features.
 */
// What an app depends on: `implementation("com.vougadev:shell")`, resolved to this project by `includeBuild`.
group = "com.vougadev"

android {
    namespace = "com.vougadev.shell"
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
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.webkit)
    // Only for the system "Turn on location" dialog (SettingsClient).
    implementation(libs.play.services.location)
    implementation(libs.appauth)
    // The native Settings screen, and the account service's pages opened over the app.
    implementation(libs.androidx.preference)
    // Sign in with Google natively, for the account service's page (Google refuses a WebView).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.androidx.browser)
    // Push from the notification hub: inert unless the app was built with its Firebase file.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
