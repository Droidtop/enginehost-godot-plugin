// Enginehost's plugin API, compiled against and never shipped: at run time the host supplies these
// classes. The source is Enginehost's own plugin-api/, checked out into enginehost-android/build/enginehost
// by the Droidtop/enginehost .github/actions/plugin-api action at the commit the workflow pins
// (Droidtop/tracker#768: this repository used to commit a copy, which had fallen behind, and lines
// 4.0 and 4.1 a second copy of EngineHost on top of it).
plugins { id("com.android.library") }

val enginehostApi = rootProject.file("build/enginehost/plugin-api/src/main/java")
check(enginehostApi.isDirectory) {
    "Enginehost's plugin API is not checked out: clone Droidtop/enginehost into enginehost-android/build/enginehost " +
        "at the commit .github/workflows/enginehost-android.yml pins its plugin-api action to"
}

android {
    namespace = "dev.enginehost.api.stubs"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").java.srcDir(enginehostApi)
}
