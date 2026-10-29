import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.twtrubiks.treesay"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.twtrubiks.treesay"
        // Android 9；java.time 從 API 26 就內建，不需要 core library desugaring
        minSdk = 28
        targetSdk = 36
        // 發版時由 android-release.yml 帶進來：versionCode 是 2026 年起算的分鐘數，只會越來越大，新版才裝得上去
        versionCode = providers.environmentVariable("TREESAY_VERSION_CODE").map(String::toInt).getOrElse(1)
        versionName = providers.environmentVariable("TREESAY_VERSION_NAME").getOrElse("0.1")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 給別人下載的 APK（GitHub Release）一律用同一把金鑰簽：換了金鑰，手機上的舊版就不能直接更新，
    // 只能移除重裝，日記會一起刪掉。金鑰放在 GitHub Secrets，由 android-release.yml 解開後用環境變數帶進來
    val releaseKeystore = providers.environmentVariable("TREESAY_KEYSTORE")
    if (releaseKeystore.isPresent) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore.get())
            storeType = "pkcs12"
            storePassword = providers.environmentVariable("TREESAY_KEYSTORE_PASSWORD").get()
            keyAlias = "treesay"
            keyPassword = providers.environmentVariable("TREESAY_KEYSTORE_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            // R8 刪掉沒用到的程式碼並最佳化，Compose 在 release 版會比 debug 版順很多
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 沒有發版金鑰（自己 build、錄影的 CI）就用這台機器的 debug 金鑰簽，能直接安裝，也能覆蓋同一台機器打包的 debug 版
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<Test>().configureEach {
    // CI 只留得下 gradle-build.txt，測試失敗要直接印出期望值與實際值
    testLogging { exceptionFormat = TestExceptionFormat.FULL }
    // QuestionsTest、PromptsTest 拿網頁版的題庫與 prompt 來比對：只改了網頁版，測試也要重跑，不能直接用 build cache 的結果
    inputs.file(rootDir.resolve("../backend/questions.py"))
        .withPropertyName("webQuestionBank")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.file(rootDir.resolve("../backend/prompts.py"))
        .withPropertyName("webPrompts")
        .withPathSensitivity(PathSensitivity.NONE)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.anthropic.java)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.sqlite.jdbc)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    // 錄影時在模擬器上開一個假的 Claude 伺服器，CI 沒有真的 key 也能走完種樹與週報
    androidTestImplementation(libs.mockwebserver)
}
