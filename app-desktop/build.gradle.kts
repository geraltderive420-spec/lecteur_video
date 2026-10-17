import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// Same TMDB key as the Android build: local.properties (not versioned) or the TMDB_API_KEY environment variable.
val tmdbApiKey: String = run {
    val file = rootProject.file("local.properties")
    val fromFile = if (file.exists()) Properties().apply { file.inputStream().use(::load) }.getProperty("tmdb.apiKey") else null
    (fromFile ?: System.getenv("TMDB_API_KEY") ?: "").trim()
}

val appVersion = "1.0.2"

val generateBuildConfig by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildconfig")
    inputs.property("key", tmdbApiKey)
    inputs.property("version", appVersion)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("com/lecteur/desktop")
        dir.mkdirs()
        dir.resolve("BuildConfig.kt").writeText(
            """
            |package com.lecteur.desktop
            |
            |object BuildConfig {
            |    const val TMDB_API_KEY = "$tmdbApiKey"
            |    const val VERSION = "$appVersion"
            |}
            |""".trimMargin()
        )
    }
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir(generateBuildConfig)
        // The TMDB client of core:network is plain JVM code (Retrofit + kotlinx.serialization): compiled here as is,
        // without the Android library around it.
        kotlin.srcDir("../core/network/src/main/kotlin/com/lecteur/core/network/tmdb")
        // Same for the platform-free parts of core:player: resume rules, track choice, queue, sleep timer, display modes.
        listOf("resume", "tracks", "queue", "sleep", "display").forEach {
            kotlin.srcDir("../core/player/src/main/kotlin/com/lecteur/core/player/$it")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.javax.inject)

    implementation(libs.vlcj)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jmdns)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

// libVLC is shipped next to the app (bundled, no VLC install needed): copied from the VLC_DIR environment variable,
// else from the standard VLC 64-bit install of the build machine.
val vlcSource: File = File(System.getenv("VLC_DIR") ?: "C:/Program Files/VideoLAN/VLC")

val prepareVlc by tasks.registering(Sync::class) {
    from(vlcSource) {
        include("libvlc.dll", "libvlccore.dll", "plugins/**", "hrtfs/**")
        // Plugins the player never uses (GUI skins, web plugins, visualisations, outputs to streaming servers)
        exclude("plugins/gui/**", "plugins/visualization/**", "plugins/lua/**", "plugins/video_splitter/**", "plugins/stream_out/**")
    }
    into(layout.buildDirectory.dir("vlc-resources/windows/vlc"))
    // plugins.dat: without it libVLC scans every plugin DLL at each start (a second or more)
    doLast {
        val cacheGen = File(vlcSource, "vlc-cache-gen.exe")
        val plugins = destinationDir.resolve("plugins")
        if (cacheGen.isFile && plugins.isDirectory) {
            runCatching { ProcessBuilder(cacheGen.absolutePath, plugins.absolutePath).inheritIO().start().waitFor() }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.lecteur.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx1g", "-Dsun.java2d.d3d=true", "-Dsun.awt.noerasebackground=true")

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "LecteurMedia"
            packageVersion = appVersion
            description = "Lecteur Média : bibliothèque de films et séries, lecture tout format, réception depuis le téléphone"
            vendor = "Lecteur Média"
            copyright = "© 2026 Lecteur Média"
            modules("java.sql", "java.naming", "jdk.unsupported", "java.net.http", "jdk.crypto.ec")
            appResourcesRootDir.set(layout.buildDirectory.dir("vlc-resources"))

            windows {
                menuGroup = "Lecteur Média"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f3b1a52-8f0e-4b8e-9a51-2c7d4e9b1f10"
                iconFile.set(project.file("icons/app.ico"))
            }
        }
    }
}

tasks.matching { it.name in setOf("prepareAppResources", "run", "createDistributable", "packageExe", "packageMsi") }
    .configureEach { dependsOn(prepareVlc) }

// Self-test (not shipped): scans a sample library, renders every screen off-screen, plays files and drives the receiver
// the way the phone does. ./gradlew :app-desktop:selfTest -PselfTestDir=<folder with Films/ and Series/>
val selftest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("selfTest") {
    dependsOn(prepareVlc)
    classpath = selftest.runtimeClasspath
    mainClass.set("com.lecteur.desktop.SelfTestKt")
    val dir = (findProperty("selfTestDir") as String?) ?: layout.buildDirectory.dir("selftest").get().asFile.absolutePath
    args(dir)
    environment("LECTEUR_DATA_DIR", (findProperty("selfTestData") as String?) ?: "$dir/data")
    (findProperty("selfTestScanOnly") as String?)?.let { environment("SELFTEST_SCAN_ONLY", it) }
    systemProperty("compose.application.resources.dir", layout.buildDirectory.dir("vlc-resources/windows").get().asFile.absolutePath)
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.awt.noerasebackground=true")
}
