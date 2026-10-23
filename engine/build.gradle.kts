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
    implementation("org.jgrapht:jgrapht-core:1.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
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
