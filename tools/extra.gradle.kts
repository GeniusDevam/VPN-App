// VPNAPP_EXTRA_BEGIN  (appended to ics-openvpn main/build.gradle.kts by tools/prepare.sh)
// Every CPU architecture Android ships on: 64- and 32-bit ARM (phones, tablets, Android Go and TV devices) and 64- and 32-bit x86
// (Chromebooks, a few tablets and TV boxes, emulators). One build produces a universal APK plus one smaller APK per architecture.
// -PvpnappAbi=<abi> builds a single architecture instead (quick local builds).
val vpnappAllAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val vpnappSingleAbi = project.findProperty("vpnappAbi") as String?

android {
    // GitHub runners ship this NDK (upstream pins an older 30.0 build that is not preinstalled).
    ndkVersion = "30.0.16248370"
    buildFeatures { compose = true }
    defaultConfig {
        // Android 6.0. The floor is set by the encrypted storage (Android Keystore AES-GCM keys exist from API 23) and by current
        // AndroidX/Compose, which also require API 23. Lint's NewApi check (below) fails the build if anything newer is called unguarded.
        minSdk = 23
        applicationId = "io.github.geniusdevam.vpnapp"
        val code = (project.findProperty("vpnappVersionCode") as String?)?.toIntOrNull() ?: 1
        versionCode = code
        versionName = "1.0.$code"
        ndk {
            abiFilters.clear()
            if (vpnappSingleAbi != null) abiFilters.add(vpnappSingleAbi)
        }
    }
    splits {
        abi {
            isEnable = vpnappSingleAbi == null
            reset()
            include(*vpnappAllAbis.toTypedArray())
            isUniversalApk = true
        }
    }
    lint {
        // Calling an API that does not exist on the oldest supported Android version crashes there at runtime. Fail instead.
        checkOnly += "NewApi"
        abortOnError = true
        ignoreWarnings = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
// VPNAPP_EXTRA_END
