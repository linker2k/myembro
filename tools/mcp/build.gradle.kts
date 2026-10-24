plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(libs.kotlin.test)
}
application { mainClass.set("io.github.linker2k.myembro.mcp.MainKt"); applicationName = "myembro-mcp" }
tasks.processResources {
    from(rootProject.file("engine/src/test/resources/font-snapshots")) {
        include("*-outline.json.gz", "*-satin.json.gz", "*-OFL.txt", "manifest.json", "README.md")
        into("font-snapshots")
    }
}

tasks.register<Exec>("checkStdio") {
    dependsOn(tasks.installDist)
    commandLine("python3",file("check_stdio.py"),layout.buildDirectory.file("install/myembro-mcp/bin/myembro-mcp").get().asFile)
}
tasks.check { dependsOn("checkStdio") }
