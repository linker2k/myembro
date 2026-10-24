plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Dual EPL/EDL; distributed under EDL-1.0 (see THIRD_PARTY_NOTICES.md).
    implementation("org.locationtech.jts:jts-core:1.20.0")
    // NetworkX Euler augmentation replacement: LGPL-2.1-or-later option (see notices).

    // model fill routing, LGPL-2.1-or-later option (see THIRD_PARTY_NOTICES).
    implementation("org.jgrapht:jgrapht-core:1.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Model tatami routing: LGPL-2.1-or-later option, see THIRD_PARTY_NOTICES.
    testImplementation(libs.kotlin.test)
}

// Standalone replay of fixed SVG matrix inputs through OUR JVM engine only.
// Official Ink/Stitch binaries and Python modules are never launched by this task.
val oracleSourceSet = sourceSets.create("oracle")
oracleSourceSet.compileClasspath += sourceSets.main.get().output
oracleSourceSet.runtimeClasspath += sourceSets.main.get().output
configurations[oracleSourceSet.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[oracleSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

tasks.register<JavaExec>("runInkstitchWorkerMatrix") {
    group = "verification"
    description = "Generate worker matrix PES and identity manifest using the Kotlin port"
    classpath = oracleSourceSet.runtimeClasspath
    mainClass.set("io.github.linker2k.myembro.engine.inkstitch.oracle.WorkerMatrixCli")
    workingDir = rootProject.projectDir
    maxHeapSize = "1g"
    args(
        providers.gradleProperty("workerMatrix").orElse("/tmp/myembro-quality-gate/matrix-inkstitch.json").get(),
        providers.gradleProperty("workerOracleRoot").orElse("/tmp/myembro-inkstitch-oracle").get(),
        providers.gradleProperty("workerOutput").orElse("/tmp/myembro-port-out/worker").get(),
        providers.gradleProperty("workerFeatures").orElse("stitch_plan,ties,stroke").get(),
        providers.gradleProperty("workerOnlyId").orElse("").get(),
    )
}


tasks.register<JavaExec>("verifyInkstitchWorkerBoundary") {
    group = "verification"
    description = "Check analytic SVG-to-manual-CSP boundary fixtures without running Ink/Stitch"
    classpath = oracleSourceSet.runtimeClasspath
    mainClass.set("io.github.linker2k.myembro.engine.inkstitch.oracle.WorkerBoundaryChecks")
    workingDir = rootProject.projectDir
}

// Extended local input sweep; deliberately not a dependency of the <=10-case CI test tier.
tasks.register<JavaExec>("generateTatamiSweep") {
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.linker2k.myembro.engine.inkstitch.elements.TatamiSweepFixtures")
    maxHeapSize = "1g"
    args(providers.gradleProperty("tatamiSweepDir").orElse("/tmp/myembro-tatami-sweep").get(),
        providers.gradleProperty("tatamiSweepSizes").orElse((5..90).joinToString(",")).get())
}

// Oracle regression tests reuse the pinned Inkex curve boundary instead of a different SVG flattener.
sourceSets.test {
    compileClasspath += oracleSourceSet.output
    runtimeClasspath += oracleSourceSet.output
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

// Explicit local sample composition; never part of check/CI.
tasks.register<JavaExec>("makeSampler") {
    group = "verification"
    description = "Pack PES samples into a single-color sewout sampler and labeled preview"
    classpath = oracleSourceSet.runtimeClasspath
    mainClass.set("io.github.linker2k.myembro.engine.inkstitch.oracle.SamplerCli")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
    args(providers.gradleProperty("samplerOutput").orElse("/tmp/myembro-sewtest/SAMPLER").get(),
        providers.gradleProperty("samplerMaxMm").orElse("60").get(),
        providers.gradleProperty("samplerGapMm").orElse("5").get())
    args(providers.gradleProperty("samplerInputs").orElse("").get().split('|').filter(String::isNotBlank))
}

// Python gate consumes dimensions emitted from the same runtime HoopSpec as the app.
tasks.register<JavaExec>("exportGateHoop") {
    group = "verification"
    classpath = oracleSourceSet.runtimeClasspath
    mainClass.set("io.github.linker2k.myembro.engine.inkstitch.oracle.HoopConfigCli")
    args(providers.gradleProperty("gateHoopOutput").orElse(layout.buildDirectory.file("hoop-spec.json").get().asFile.path).get())
}

// Writes a classpath for a separately launched, frozen local JVM sweep; never a check dependency.
tasks.register("koreanTtfSweepClasspath") {
    group = "verification"
    dependsOn(tasks.named("testClasses"), tasks.named("oracleClasses"))
    doLast {
        val out = file(providers.gradleProperty("koreanTtfSweepClasspath").orElse("/tmp/myembro-ttf-fill-sweep-reduced/classpath.txt").get())
        out.parentFile.mkdirs()
        out.writeText(sourceSets.test.get().runtimeClasspath.asPath)
    }
}

// Release sweeps render many patterns in parallel.
tasks.test { maxHeapSize = "3g" }

tasks.register<JavaExec>("makeS4Samples") {
    group = "verification"
    description = "Generate original 30mm flower/house redwork samples with the S4 pipeline"
    classpath = oracleSourceSet.runtimeClasspath
    mainClass.set("io.github.linker2k.myembro.engine.inkstitch.oracle.S4SamplesCli")
    args("/tmp/myembro-sewtest/s4")
}

tasks.register<JavaExec>("sweepS4Fill") {
    group = "verification"
    classpath = oracleSourceSet.runtimeClasspath
    mainClass.set("io.github.linker2k.myembro.engine.inkstitch.oracle.S4FillSweepCli")
    args("/tmp/myembro-sewtest/s4/fill-sweep")
}

// Opt-in synthetic 12MP photo timing; not part of CI's representative fixture tier.
tasks.register<JavaExec>("photoTiming") {
    classpath = oracleSourceSet.runtimeClasspath
    mainClass.set("io.github.linker2k.myembro.engine.inkstitch.oracle.PhotoTimingCli")
    maxHeapSize = "1g"
    args(providers.gradleProperty("photoTimingArgs").orElse("192,baseline,full").get().split(','))
}

// Explicit Hangul trial only. "trace" requires a caller-selected width before creating any PES.
tasks.register<JavaExec>("hangulSatinSample") {
    group = "verification"
    classpath = oracleSourceSet.runtimeClasspath
    mainClass.set("io.github.linker2k.myembro.engine.inkstitch.oracle.HangulSatinCli")
    systemProperty("java.awt.headless", "true")
    args(providers.gradleProperty("hangulInput").orElse("/tmp/myembro-sewtest/krs").get(),
        providers.gradleProperty("hangulOutput").orElse("/tmp/myembro-sewtest/krs/KRSAT1").get(),
        providers.gradleProperty("strokeWidthMm").orElse("trace").get())
}

// Explicit 5×30×3 public-font calculation; never a check/CI dependency.
tasks.register<JavaExec>("hangulSatinSweep") {
    group = "verification"
    classpath = oracleSourceSet.runtimeClasspath
    mainClass.set("io.github.linker2k.myembro.engine.inkstitch.oracle.HangulSatinSweepCli")
    systemProperty("java.awt.headless", "true")
    maxHeapSize = "2g"
    args(providers.gradleProperty("hangulSweepRoot").orElse("/tmp/myembro-port-out/letter/hangul-sweep-v1").get())
}

// Original synthetic PNG probe; no app integration or private fixtures.
tasks.register<JavaExec>("imageToDesignProbe") {
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.linker2k.myembro.engine.inkstitch.oracle.ImageToDesignProbeCli")
    maxHeapSize = "1g"
    args(providers.gradleProperty("imageProbeInput").orElse("/tmp/myembro-port-out/editor/image-to-design/inputs").get(),
        providers.gradleProperty("imageProbeOutput").orElse("/tmp/myembro-port-out/editor/image-to-design").get(),
        providers.gradleProperty("imageProbeColors").orElse("4").get())
}

// Opt-in diagnostic, no UI/default changes: exact machine edge multiplicity and source geometry.
tasks.register<JavaExec>("measureSingleLinePasses") {
    group = "verification"
    classpath = oracleSourceSet.runtimeClasspath
    mainClass.set("io.github.linker2k.myembro.engine.inkstitch.oracle.SingleLinePassCli")
    workingDir = rootProject.projectDir
    args(providers.gradleProperty("passOutput").orElse("/tmp/myembro-port-out/letter/single-line-passes").get(),
        providers.gradleProperty("passFonts").orElse("/tmp/myembro-fontq-independent/fonts").get(),
        providers.gradleProperty("passText").orElse("Yeonwoo").get(),
        providers.gradleProperty("passHeight").orElse("10").get())
}

// One authoritative wire catalog, bundled unchanged for Android-free validation.
tasks.processResources {
    from(rootProject.file("docs/agent/tools.json")) { into("agent") }
}
tasks.register<JavaExec>("runAgentScenario") {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.linker2k.myembro.engine.agent.AgentScenarioCli")
    workingDir = rootProject.projectDir
    args(providers.gradleProperty("agentScenario").orElse("").get(), providers.gradleProperty("agentOutput").orElse("").get())
    if (providers.gradleProperty("agentDryRun").orElse("false").get().toBooleanStrict()) args("--dry-run")
}

tasks.processResources {
    from(rootProject.file("app/src/main/assets/samples/sample_heart_running.pes")) { into("agent/builtins") }
    from(rootProject.file("app/src/main/assets/designs/myembro_logo.pes")) { into("agent/builtins") }
}

// Same policy data and redistributable fonts used by the phone; no network resolution.
tasks.processResources {
    from(rootProject.file("app/src/main/assets/lettering/ttf-fill-height-ranges.json")) { into("lettering") }
    from(rootProject.file("app/src/main/assets/fonts/bundled")) { into("agent/fonts") }
    from(rootProject.file("app/src/main/assets/fonts/inkstitch-catalog.json")) { into("agent/fonts") }
}

// Same redistributable sample catalog used by the Android built-in gallery.
tasks.processResources {
    from(rootProject.file("app/src/main/assets/samples")) {
        include("*.pes", "manifest.json", "LICENSES.md", "CC0-1.0.txt")
        into("agent/designs")
    }
}

// Identical local Fluent/CLDR catalog, 30 verified SVGs and their notices; no downloader in JVM tools.
tasks.processResources {
    from(rootProject.file("app/src/main/assets/emoji")) { into("emoji") }
}
