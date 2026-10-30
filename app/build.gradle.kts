import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }

// 密钥从 local.properties 读（该文件不进 git）。
// 缺失时留空，App 里仍可手动填。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val deepseekKey: String = localProps.getProperty("DEEPSEEK_API_KEY", "")

android {
    namespace = "com.cielo.bot"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.cielo.bot"
        minSdk = 26
        targetSdk = 28          // 29+ blocks exec from the app data dir
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepseekKey\"")
    }
    buildFeatures { buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/*") }
    kotlinOptions { jvmTarget = "17" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
