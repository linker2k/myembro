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
