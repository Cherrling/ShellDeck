import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val signingVariables = listOf(
    "ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD",
)
val signingValues = signingVariables.associateWith { providers.environmentVariable(it).orNull }

val buildTime = providers.environmentVariable("SHELLDECK_BUILD_TIME").orElse("Local build (unrecorded)").get()
val sourceRevision = providers.environmentVariable("SHELLDECK_SOURCE_REVISION").orElse("local").get()
require(buildTime == "Local build (unrecorded)" || Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} UTC\\+8").matches(buildTime))
require(sourceRevision == "local" || Regex("[0-9a-f]{40}").matches(sourceRevision))

android {
    namespace = "cc.cherr.shelldeck"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "29.0.14206865"
    sourceSets.getByName("main").jniLibs.srcDir(rootProject.layout.buildDirectory.dir("mosh/jniLibs"))
    sourceSets.getByName("main").assets.srcDir(rootProject.layout.buildDirectory.dir("mosh/assets"))

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions { annotationProcessorOptions { arguments["room.schemaLocation"] = "$projectDir/schemas" } }
        applicationId = "cc.cherr.shelldeck"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "SOURCE_REVISION", "\"${sourceRevision.take(12)}\"")
    }

    signingConfigs {
        create("release") {
            signingValues["ANDROID_KEYSTORE_PATH"]?.takeIf { it.isNotBlank() }?.let {
                storeFile = file(it)
            }
            storePassword = signingValues["ANDROID_KEYSTORE_PASSWORD"]
            keyAlias = signingValues["ANDROID_KEY_ALIAS"]
            keyPassword = signingValues["ANDROID_KEY_PASSWORD"]
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "ShellDeck Dev")
        }
        release {
            isDebuggable = false
            // Enable shrinking after terminal/SSH integration has release-mode coverage.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("androidTest").assets.srcDir("schemas")
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        resources.merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/LICENSE", "META-INF/NOTICE")
    }
    lint {
        warningsAsErrors = true
        // Version upgrades are deliberate changes to the tested toolchain, not lint failures.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

kotlin { jvmToolchain(17) }

val validateReleaseSigning by tasks.registering {
    doLast {
        val missing = signingVariables.filter { signingValues[it].isNullOrBlank() }
        check(missing.isEmpty()) { "Release signing is required. Missing: ${missing.joinToString()}" }
        check(file(signingValues.getValue("ANDROID_KEYSTORE_PATH")!!).isFile) {
            "Release keystore does not exist."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

dependencies {
    implementation(libs.sshj)
    implementation(libs.bcprov)
    implementation(libs.bcpkix)
    implementation(libs.slf4j.nop)
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)
    implementation(project(":terminal-view"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// Integration results depend on a live, temporary sshd and must not be replayed from cache.
tasks.withType<Test>().configureEach {
    val liveSsh = providers.environmentVariable("SSH_TEST_DIR").isPresent
    inputs.property("liveSshIntegration", liveSsh)
    outputs.upToDateWhen { !liveSsh }
    outputs.cacheIf { !liveSsh }
}

// Native sources are built explicitly so CI can cache the expensive cross-compilation separately.
val validateMoshNative by tasks.registering {
    doLast {
        val root = rootProject.layout.buildDirectory.dir("mosh").get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        for (path in listOf("scripts/build_mosh.py", "native/mosh/sources.json", "native/mosh/pty.c")) {
            digest.update(rootProject.file(path).readBytes())
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        check(root.resolve("fingerprint").readText() == fingerprint) { "Mosh sources changed: rerun python3 scripts/build_mosh.py" }
        val abis = providers.gradleProperty("moshAbis").orElse("arm64-v8a,armeabi-v7a,x86_64").get().split(",")
        for (abi in abis) for (name in listOf("libmosh-client.so", "libshelldeck-pty.so")) {
            check(root.resolve("jniLibs/$abi/$name").isFile) {
                "Missing Mosh native binary ($abi/$name). Run python3 scripts/build_mosh.py first."
            }
        }
        check(root.resolve("assets/terminfo").isDirectory)
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(validateMoshNative) }
