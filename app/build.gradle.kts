plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val generatedLicenseAssets = layout.buildDirectory.dir("generated/licenseAssets")

android {
    namespace = "io.github.kid141252010.fantastic"
    compileSdk = 35
    sourceSets.getByName("main").assets.srcDir(generatedLicenseAssets)
    // Optional private fonts exist only in this checkout; CI without them uses Android fonts.
    val localAmFontAssets = rootProject.file(".work/local-am-fonts/assets")
    val includeLocalAmFonts = providers.gradleProperty("includeLocalAmFonts").orNull
    if (includeLocalAmFonts == "true") {
        require(localAmFontAssets.isDirectory) { "Local Apple font assets are missing: $localAmFontAssets" }
    }
    if (includeLocalAmFonts != "false" && localAmFontAssets.isDirectory) {
        val requiredFonts = listOf(
            "sf_pro_android_ui.ttf", "sf_pro_rounded_android_ui.ttf",
            "pingfang.ttc", "hiragino_sans.ttc", "apple_sd_gothic_neo.ttc",
        )
        val missingFonts = requiredFonts.filterNot { localAmFontAssets.resolve("am-fonts/$it").isFile }
        require(missingFonts.isEmpty()) { "Incomplete local Apple font assets: ${missingFonts.joinToString()}" }
        sourceSets.getByName("main").assets.srcDir(localAmFontAssets)
        // Map the CJK collections directly from the APK; share their buffers across text sizes.
        androidResources.noCompress += "ttc"
    }

    defaultConfig {
        applicationId = "io.github.kid141252010.fantastic"
        minSdk = 26
        targetSdk = 35
        val releaseVersionCode = 8
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt()?.coerceAtLeast(releaseVersionCode) ?: releaseVersionCode
        versionName = "1.3.1"
        // 设置页「歌词诊断」显示构建对应的提交，便于确认手机上装的是哪个 nightly
        buildConfigField("String", "GIT_SHA", "\"${(System.getenv("GITHUB_SHA") ?: "local").take(7)}\"")
    }

    // 固定的调试签名（仓库里的 app/debug.keystore），每次 CI 构建签名一致，新包能直接覆盖安装。
    // 这把密钥是公开的，只用于调试版。发行包由专用密钥单独签名。
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.register<Copy>("copyApacheLicense") {
    from(rootProject.file("LICENSE"))
    into(generatedLicenseAssets)
}
tasks.register<Copy>("copyThirdPartyLicenses") {
    from(rootProject.file("native/vendor/temari/LICENSE")) { rename { "temari.txt" } }
    from(rootProject.file("native/vendor/alac/LICENSE")) { rename { "alac.txt" } }
    into(rootProject.file("app/src/main/assets/licenses"))
}


tasks.named("preBuild").configure {
    dependsOn("copyApacheLicense", "copyThirdPartyLicenses")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    // 玻璃效果：背后内容模糊（API 31+ RenderEffect，低版本退化为半透明底色）
    implementation("dev.chrisbanes.haze:haze:1.1.1")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
}
