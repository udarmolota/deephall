import java.security.MessageDigest
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
}

// Raise this when a game payload has to be told apart from the last one by hand. Any change to the
// files themselves changes the stamp on its own: it also carries a SHA-1 of the packed content.
val deephallGameVersion = "1"

/**
 * Packs the staged Deephall game (deephall-game/ at the repository root) into the debug APK's
 * assets as game/deephall-game.zip, with game/deephall-game.version beside it. BundledGame reads
 * both on the phone and replaces the instance's game files when the stamp changes.
 *
 * Only mountaincore.jar, lwjgl336/ and mods/ are packed: the game builds its assets/ folder from
 * mods/ when it starts. Entries are STORED and the archive is kept uncompressed in the APK
 * (noCompress below): the jar, the PNGs and most of the audio are compressed already, and a stored
 * asset extracts at disk speed.
 */
abstract class PackDeephallGame : DefaultTask() {
    @get:Internal
    abstract val gameDir: DirectoryProperty

    @get:Input
    abstract val gameVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val gameFiles: FileTree
        get() = gameDir.asFileTree.matching { include(*PAYLOAD.map { "$it/**" }.toTypedArray(), GAME_JAR) }

    @TaskAction
    fun pack() {
        val root = gameDir.get().asFile
        val missing = (listOf(GAME_JAR) + PAYLOAD).filter { !File(root, it).exists() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "The Deephall game files are not staged: $root must hold $GAME_JAR, " +
                PAYLOAD.joinToString(", ") { "$it/" } + " (missing: ${missing.joinToString(", ")}). " +
                "Copy them from a Deephall build; the folder is git-ignored and never committed.")
        }

        val outDir = outputDir.get().asFile.resolve("game")
        outDir.deleteRecursively()
        outDir.mkdirs()

        // Sorted, so the same files always make the same archive and the same stamp.
        val paths = root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it == GAME_JAR || PAYLOAD.any { dir -> it.startsWith("$dir/") } }
            .sorted().toList()

        val sha1 = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(1 shl 20)
        ZipOutputStream(outDir.resolve("deephall-game.zip").outputStream().buffered(1 shl 20)).use { zip ->
            for (path in paths) {
                val file = File(root, path)
                // A STORED entry states its size and CRC up front, so each file is read twice.
                val crc = CRC32()
                sha1.update(path.toByteArray(Charsets.UTF_8))
                file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                        sha1.update(buffer, 0, n)
                    }
                }
                val entry = ZipEntry(path).apply {
                    method = ZipEntry.STORED
                    size = file.length()
                    compressedSize = file.length()
                    this.crc = crc.value
                    time = DOS_EPOCH_MS
                }
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip, buffer.size) }
                zip.closeEntry()
            }
        }

        val digest = sha1.digest().joinToString("") { "%02x".format(it) }
        outDir.resolve("deephall-game.version").writeText("${gameVersion.get()}+$digest\n")
        logger.lifecycle("Packed ${paths.size} Deephall game files, stamp ${gameVersion.get()}+$digest")
    }

    private companion object {
        const val GAME_JAR = "mountaincore.jar"
        val PAYLOAD = listOf("lwjgl336", "mods")
        // A fixed entry time keeps the archive byte-for-byte the same for the same files.
        const val DOS_EPOCH_MS = 315_532_800_000L
    }
}

val packDeephallGame = tasks.register<PackDeephallGame>("packDeephallGame") {
    gameDir.set(rootProject.layout.projectDirectory.dir("deephall-game"))
    gameVersion.set(deephallGameVersion)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// Short commit hash of the working tree, with a "+" appended when there are uncommitted changes.
// versionName alone cannot identify a build: 1.4.7 and 1.4.7v4 both reported as "1.4.7 (147)" in
// bug reports, so we could not tell which build a crash came from. This is computed at build time
// and never has to be remembered. Falls back to "unknown" outside a git checkout or without git,
// so the build never depends on it.
val gitBuildId: String = try {
    val hash = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        workingDir = rootProject.projectDir
    }.standardOutput.asText.get().trim()
    val dirty = providers.exec {
        commandLine("git", "status", "--porcelain")
        workingDir = rootProject.projectDir
    }.standardOutput.asText.get().trim().isNotEmpty()
    if (hash.isEmpty()) "unknown" else hash + if (dirty) "+" else ""
} catch (e: Exception) {
    "unknown"
}

val hasSigningConfig = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD"
).all { localProperties[it] != null }

android {
    namespace = "com.zomdroid"
    compileSdk = 35

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(localProperties["RELEASE_STORE_FILE"].toString())
                storePassword = localProperties["RELEASE_STORE_PASSWORD"].toString()
                keyAlias = localProperties["RELEASE_KEY_ALIAS"].toString()
                keyPassword = localProperties["RELEASE_KEY_PASSWORD"].toString()

            }
        }
    }

    defaultConfig {
        applicationId = "com.zomdroid"
        minSdk = 30
        targetSdk = 35
        versionCode = 150
        versionName = "1.5.0"

        buildConfigField("String", "GIT_BUILD_ID", "\"$gitBuildId\"")

        // JavaSteam + protobuf + kotlin stack push past the 64K method limit.
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Align native .so segments to 16 KB pages (Android 15+ requirement).
                // NDK r27 doesn't enable this by default; the flag adds -Wl,-z,max-page-size=16384.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                // Package libc++_shared.so: the Mach-O loader (macho_loader.c) resolves the C++
                // runtime imports of the game's macOS dylibs against it at run time.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            // The debug build is the Deephall launcher (see the buildTypes block below), so it
            // should not come out of the build with Zomdroid's name on it.
            val launcherName = if (variant.buildType.name == "debug") "deephall"
                    else "zomdroid-${variant.buildType.name}"
            outputImpl.outputFileName = "$launcherName-${variant.versionName}.apk"
        }
    }

    buildTypes {
        if (hasSigningConfig) {
            release {
                isMinifyEnabled = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
            // A debug build must be able to sit next to the Zomdroid the phone already plays
            // Project Zomboid on: same application id would mean a signature clash on install,
            // and the two content provider authorities below must be unique across the device.
            applicationIdSuffix = ".deephall"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // JavaSteam / protobuf / bouncycastle / kotlin bring duplicate metadata files.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/{AL2.0,LGPL2.1}",
                "**/*.proto"
            )
        }
    }
    androidResources {
        // The bundled Deephall game (packDeephallGame): stored as it is, see the task.
        noCompress += "deephall-game.zip"
    }
  ndkVersion = "27.3.13750724"
}

androidComponents {
    // Only the debug build is the standalone Deephall app, so only it carries the game.
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(packDeephallGame, PackDeephallGame::outputDir)
    }
}

dependencies {
    implementation(libs.gson)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.commons.io)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.legacy.support.v4)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
