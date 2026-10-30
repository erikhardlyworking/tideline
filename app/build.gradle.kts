import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }

// Upload signing is opt-in. Never fall back to the debug key for a release.
val uploadProperties = Properties()
val uploadPropertiesFile = rootProject.file("key.properties")
if (uploadPropertiesFile.isFile) uploadPropertiesFile.inputStream().use { uploadProperties.load(it) }
val uploadFields = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val uploadValues: Map<String, String?> = uploadFields.associateWith { field ->
    val environment = "TIDELINE_" + field.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
    providers.environmentVariable(environment).orNull ?: uploadProperties.getProperty(field)
}
val uploadSigningConfigured = uploadValues.values.any { it != null }
if (uploadSigningConfigured) {
    check(uploadValues.values.all { !it.isNullOrBlank() }) {
        "Upload signing is incomplete. Supply all four fields in key.properties or TIDELINE_* variables; see README.md."
    }
    check(rootProject.file(uploadValues.getValue("storeFile")!!).isFile) { "Upload keystore file does not exist." }
}
android {
    namespace = "app.tideline"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.tideline"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.2.3"
        testInstrumentationRunner = "app.tideline.WidgetInstrumentation"
    }
    signingConfigs {
        if (uploadSigningConfigured) create("upload") {
            storeFile = rootProject.file(uploadValues.getValue("storeFile")!!)
            storePassword = uploadValues.getValue("storePassword")
            keyAlias = uploadValues.getValue("keyAlias")
            keyPassword = uploadValues.getValue("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            if (uploadSigningConfigured) signingConfig = signingConfigs.getByName("upload")
        }
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
