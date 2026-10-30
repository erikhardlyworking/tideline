plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
application { mainClass.set("app.tideline.cli.MainKt") }
dependencies { implementation(project(":tide-core")); implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1") }
