plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val brandColorsDirectory = layout.buildDirectory.dir("generated/res/myembroBrand")
val generateMyembroBrandColors by tasks.registering {
    val tokens = layout.projectDirectory.file("src/main/kotlin/io/github/linker2k/myembro/ui/theme/Color.kt")
    inputs.file(tokens)
    outputs.dir(brandColorsDirectory)
    doLast {
        val source = tokens.asFile.readText()
        val colorway = Regex("val DefaultColorway = MyembroColorway\\.(\\w+)").find(source)!!.groupValues[1]
        fun color(name: String): String = "#" + requireNotNull(
            Regex("const val $name: Long = 0xFF([0-9A-Fa-f]{6})").find(source),
        ) { "Missing launch color token $name" }.groupValues[1]
        fun primary(prefix: String): String = when (colorway) {
            "BOTANICAL" -> "${prefix}SecondaryArgb"
            "INK" -> if (prefix == "Dark") "DarkTertiaryArgb" else "LightInkArgb"
            else -> "${prefix}PrimaryArgb"
        }
        val explicit = buildString {
            append("<resources>\n")
            for (prefix in listOf("Light", "Dark")) {
                val key = prefix.lowercase()
                for ((role, token) in listOf("background" to "Background", "primary" to "Primary", "ink" to "Ink", "hoop" to "Hoop")) {
                    val name = if (role == "primary") primary(prefix) else "${prefix}${token}Argb"
                    append("    <color name=\"myembro_${key}_${role}\">${color(name)}</color>\n")
                }
            }
            append("</resources>\n")
        }
        brandColorsDirectory.get().file("values/myembro_explicit.xml").asFile.apply {
            parentFile.mkdirs(); writeText(explicit)
        }
        for ((folder, prefix) in listOf("values" to "Light", "values-night" to "Dark")) {
            val primary = primary(prefix)
            val output = brandColorsDirectory.get().file("$folder/myembro_brand.xml").asFile
            output.parentFile.mkdirs()
            output.writeText("""
                <resources>
                    <color name="myembro_brand_background">${color("${prefix}BackgroundArgb")}</color>
                    <color name="myembro_brand_primary">${color(primary)}</color>
                    <color name="myembro_brand_ink">${color("${prefix}InkArgb")}</color>
                    <color name="myembro_brand_hoop">${color("${prefix}HoopArgb")}</color>
                </resources>
            """.trimIndent())
        }
    }
}

// Release builds pass -PmyembroVersion=<tag without v>; the commit count keeps versionCode increasing on main.
fun git(vararg args: String): String? = providers.exec {
    commandLine("git", *args)
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifEmpty { null }
val myembroVersion = providers.gradleProperty("myembroVersion").getOrElse("0.1.0-dev")
val gitCommit = git("rev-parse", "--short", "HEAD") ?: "unknown"
val gitCommitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1

android {
    namespace = "io.github.linker2k.myembro"
    compileSdk = 35
    sourceSets.getByName("main").res.srcDir(brandColorsDirectory)
    sourceSets.getByName("androidTest").assets.srcDir("../engine/src/test/resources")

    defaultConfig {
        applicationId = "io.github.linker2k.myembro"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = providers.gradleProperty("myembroTestRunner")
            .getOrElse("android.test.InstrumentationTestRunner")
        versionCode = gitCommitCount
        versionName = myembroVersion
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":engine"))
    implementation("me.jahnen.libaums:core:0.10.0") // BOT/SCSI only; our engine handles FAT12.
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // AI connection (relay spike) WebSocket.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.kotlin.test)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.named("preBuild").configure { dependsOn(generateMyembroBrandColors) }

android.sourceSets.getByName("test").java.srcDir(rootProject.file("tools/safety-tests/kotlin"))
