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

// --- macOS .app bundle (uses osacompile to create a real Apple Event handler) ---

tasks.register("macApp") {
    description = "Build InkUp Viewer.app for macOS file association"
    dependsOn("installDist")

    val appDir = layout.buildDirectory.dir("InkUp Viewer.app")
    val installDir = layout.buildDirectory.dir("install/inkup-viewer")

    outputs.dir(appDir)

    doLast {
        val app = appDir.get().asFile
        val install = installDir.get().asFile
        app.deleteRecursively()

        // osacompile creates a proper .app that handles 'open' Apple Events.
        // `do shell script` doesn't load the user's shell profile, so JAVA_HOME
        // must be resolved at build time and baked into the AppleScript.
        val javaHome = System.getenv("JAVA_HOME")
            ?: throw GradleException("JAVA_HOME not set — needed for the viewer launch script")
        val script = """
            on open theFiles
                set appPath to (POSIX path of (path to me))
                set viewerBin to appPath & "Contents/Resources/bin/inkup-viewer"
                set javaHome to "$javaHome"
                repeat with f in theFiles
                    do shell script "export JAVA_HOME=" & quoted form of javaHome & " && " & quoted form of viewerBin & " " & quoted form of POSIX path of f
                end repeat
            end open
        """.trimIndent()

        ProcessBuilder("osacompile", "-o", app.absolutePath, "-e", script)
            .inheritIO().start().waitFor().let { code ->
                require(code == 0) { "osacompile failed with exit code $code" }
            }

        // Copy the viewer distribution into the .app bundle's Resources
        val resources = File(app, "Contents/Resources")
        install.copyRecursively(resources, overwrite = true)

        // Kotlin's copyRecursively doesn't preserve permissions — fix bin scripts
        File(resources, "bin").listFiles()?.forEach { it.setExecutable(true) }

        // Merge file association keys into osacompile's Info.plist
        val plist = File(app, "Contents/Info.plist")
        val plistText = plist.readText()
        val additions = """
    <key>CFBundleDocumentTypes</key>
    <array>
        <dict>
            <key>CFBundleTypeName</key>
            <string>InkUp Document</string>
            <key>CFBundleTypeRole</key>
            <string>Viewer</string>
            <key>LSHandlerRank</key>
            <string>Owner</string>
            <key>CFBundleTypeExtensions</key>
            <array>
                <string>inkup</string>
            </array>
            <key>LSItemContentTypes</key>
            <array>
                <string>com.writer.inkup</string>
            </array>
        </dict>
    </array>
    <key>UTExportedTypeDeclarations</key>
    <array>
        <dict>
            <key>UTTypeIdentifier</key>
            <string>com.writer.inkup</string>
            <key>UTTypeDescription</key>
            <string>InkUp Document</string>
            <key>UTTypeConformsTo</key>
            <array>
                <string>public.data</string>
            </array>
            <key>UTTypeTagSpecification</key>
            <dict>
                <key>public.filename-extension</key>
                <array>
                    <string>inkup</string>
                </array>
            </dict>
        </dict>
    </array>"""
        plist.writeText(plistText.replace("</dict>\n</plist>", "$additions\n</dict>\n</plist>"))

        // Register with Launch Services
        ProcessBuilder(
            "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister",
            "-f", app.absolutePath
        ).inheritIO().start().waitFor()

        println("Built: ${app.absolutePath}")
        println("Test:  open -a '${app.absolutePath}' path/to/file.inkup")
    }
}
