plugins {
    kotlin("jvm")
    id("com.squareup.wire")
    application
}

wire {
    kotlin {}
}

application {
    mainClass.set("com.writer.tools.viewer.InkupViewerKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation("com.squareup.wire:wire-runtime:5.1.0")
}
