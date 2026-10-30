plugins { id("com.android.application") }
android {
    namespace = "dev.kunnect.fixture"
    compileSdk = 37
    defaultConfig { applicationId = "dev.kunnect.fixture"; minSdk = 28; targetSdk = 35; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
