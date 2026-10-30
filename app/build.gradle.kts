plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "app.tideline"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.tideline"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.1"
        testInstrumentationRunner = "app.tideline.WidgetInstrumentation"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    androidResources { noCompress += "tpk" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/tide-assets"))
    lint { abortOnError = true }
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*") }
}
kotlin { jvmToolchain(17) }
val prepareTideAssets by tasks.registering(Sync::class) {
    val pack = rootProject.file("data/packs/tideline-global.tpk")
    from(pack)
    from(rootProject.file("docs/DATA_NOTICES.txt"))
    from(rootProject.file("PRIVACY.md"))
    into(layout.buildDirectory.dir("generated/tide-assets"))
    doFirst { check(pack.isFile) { "Generate real offline data first: .venv/bin/python tools/build_android_data.py" } }
}
tasks.named("preBuild").configure { dependsOn(prepareTideAssets) }
dependencies {
    implementation(project(":tide-core"))
    implementation("androidx.work:work-runtime:2.10.1")
    testImplementation("junit:junit:4.13.2")
}
