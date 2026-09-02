plugins { id("com.android.application") }

android {
    namespace = "dev.enginehost.plugin.godot"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.enginehost.plugin.godot.v451.slot1"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.gitignore:!.ds_store:!*.scc:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~"
        // Enginehost attaches this APK's resources to the host's own
        // Resources object. Both would be compiled at the default package
        // id 0x7f, and the host's table wins every lookup, so Godot's
        // R.dimen.text_edit_height would resolve to whatever the host
        // happens to have at that id. Compile ours somewhere the host
        // will never be.
        additionalParameters += listOf("--package-id", "0x80", "--allow-reserved-package-id")
    }
}

dependencies {
    implementation("org.godotengine:godot:4.5.1.stable")
    implementation("androidx.fragment:fragment:1.8.6")
    compileOnly(project(":api"))
}
