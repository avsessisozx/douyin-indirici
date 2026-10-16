import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Sürüm numarası Windows sürümüyle ortak: douyin_indirici/__init__.py
val repoRoot = rootProject.file("..")
val appVersion: String = Regex("__version__\\s*=\\s*\"([^\"]+)\"")
    .find(File(repoRoot, "douyin_indirici/__init__.py").readText())?.groupValues?.get(1) ?: "0.0.0"
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Arayüz (index.html) ve çeviri dosyaları Windows sürümüyle ortak; derlemede uygulamaya kopyalanır.
val sharedAssetsDir = layout.buildDirectory.dir("generated/sharedAssets")
val copySharedAssets by tasks.registering(Sync::class) {
    from(File(repoRoot, "douyin_indirici/ui")) { include("index.html") }
    from(File(repoRoot, "ceviriler")) { into("ceviriler") }
    into(sharedAssetsDir)
}

android {
    namespace = "io.github.avsessisozx.douyinindirici"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.avsessisozx.douyinindirici"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Her derleme depodaki aynı anahtarla imzalanır; yeni sürüm eskisinin üzerine kurulabilir.
    // Bu anahtar yalnızca kişisel kullanım içindir.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/douyin-indirici.jks")
            storePassword = "douyinindirici"
            keyAlias = "douyinindirici"
            keyPassword = "douyinindirici"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    sourceSets["main"].assets.srcDir(sharedAssetsDir)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("vectorsDir", File(repoRoot, "tests/vectors").absolutePath)
            it.systemProperty("translationsDir", File(repoRoot, "ceviriler").absolutePath)
        }
    }

    lint {
        abortOnError = false
    }
}

tasks.named("preBuild") { dependsOn(copySharedAssets) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
