plugins { id("com.android.application") }

android {
    namespace = "dev.enginehost.plugin.godot"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.enginehost.plugin.godot.v414.slot1"
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
    packaging {
        jniLibs {
            // CI drops a source-built libgodot_android.so (Godot 4.1.4 with
            // Enginehost's user:// and pack-key changes; stock otherwise,
            // spine-godot needs a 4.2 header) into src/main/jniLibs. The
            // org.godotengine AAR carries upstream's library at the same
            // path; the app source set is merged first, so pickFirst keeps
            // ours. Every ABI the bundle ships, or upstream's library wins
            // for the ones left out and that ABI silently loses the changes.
            pickFirsts += listOf(
                "lib/arm64-v8a/libgodot_android.so",
                "lib/arm64-v8a/libc++_shared.so",
                "lib/x86_64/libgodot_android.so",
                "lib/x86_64/libc++_shared.so",
            )
        }
    }
}

dependencies {
    // This engine library was built with Kotlin 1.6 and asks for
    // kotlin-stdlib-jdk7/jdk8 1.6.21; androidx.fragment brings stdlib 1.8.22,
    // which absorbed those two artifacts, and the same classes then arrive
    // twice (checkDuplicateClasses). The BOM moves jdk7/jdk8 to 1.8.22,
    // where they are empty.
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
    implementation("org.godotengine:godot:4.1.4.stable")
    implementation("androidx.fragment:fragment:1.8.6")
    compileOnly(project(":api"))
}
