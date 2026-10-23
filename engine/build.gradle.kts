plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Dual EPL/EDL; distributed under EDL-1.0 (see THIRD_PARTY_NOTICES.md).
    implementation("org.locationtech.jts:jts-core:1.20.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(libs.kotlin.test)
}

// Replays only our engine against the frozen SVG inputs. Never invokes Ink/Stitch Python/binaries.
tasks.register<Test>("satinOracle") {
    group = "verification"
    description = "Generate S2 satin_column oracle-matrix PES files and a provenance manifest"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*SatinOracleReproductionTest") }
    systemProperty("myembro.oracleRoot", providers.gradleProperty("oracleRoot").orElse("/tmp/myembro-inkstitch-oracle").get())
    systemProperty("myembro.matrixFile", providers.gradleProperty("matrixFile").orElse("/tmp/myembro-quality-gate/matrix-inkstitch.json").get())
    systemProperty("myembro.portOutput", providers.gradleProperty("portOutput").orElse("/tmp/myembro-port-out/letter").get())
    outputs.upToDateWhen { false }
}

// Three human-digitized script-font names. Reads external data only, never invokes Ink/Stitch.
tasks.register<Test>("inkFontNames") {
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*InkFontNameReproductionTest") }
    systemProperty("myembro.nameOutput", providers.gradleProperty("nameOutput").orElse("/tmp/myembro-port-out/letter/jungmin").get())
    outputs.upToDateWhen { false }
}

// Four representative font-default cases, for numeric decomposition against existing official PES.
tasks.register<Test>("fontDefaultsAudit") {
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*FontDefaultsAuditTest") }
    systemProperty("myembro.defaultsAudit", providers.gradleProperty("defaultsAudit").orElse("/tmp/myembro-port-out/letter/font-defaults").get())
    outputs.upToDateWhen { false }
}
