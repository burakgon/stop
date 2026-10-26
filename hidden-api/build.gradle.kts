// Compile-only stubs for the framework's hidden binder interfaces. Nothing here is packaged:
// at runtime the daemon links against the real classes on the device's boot classpath.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.burakgon.stop.hiddenapi"
    compileSdk = 37
    enableKotlin = false

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
