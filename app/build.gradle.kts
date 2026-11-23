// تطبيق معهد عبد الله بن حذافة: غلاف WebView بلا مكتبات خارجية. Kotlin مدمج في AGP 9 (لا إضافة kotlin-android).
plugins {
    id("com.android.application")
}

android {
    namespace = "com.nafahatsy.ibh"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nafahatsy.ibh"
        minSdk = 24
        targetSdk = 37
        versionCode = 7
        versionName = "1.3.1"
    }

    buildTypes {
        release {
            // بلا signingConfig: يخرج APK غير موقَّع، ويُوقَّع خارج CI بالمفتاح الذي لا يغادر الخادم
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = false }

    lint {
        // التدقيق يعمل في خطوة منفصلة (lintRelease) ويُرفع تقريره، فلا يوقف البناء
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }
}
