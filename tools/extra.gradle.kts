// VPNAPP_EXTRA_BEGIN  (appended to ics-openvpn main/build.gradle.kts by tools/prepare.sh)
android {
    // GitHub runners ship this NDK (upstream pins an older 30.0 build that is not preinstalled).
    ndkVersion = "30.0.16248370"
    buildFeatures { compose = true }
    defaultConfig {
        minSdk = 26
        applicationId = "io.github.geniusdevam.vpnapp"
        val code = (project.findProperty("vpnappVersionCode") as String?)?.toIntOrNull() ?: 1
        versionCode = code
        versionName = "1.0.$code"
        // Phones running Android 16 are arm64; building one ABI keeps CI fast and the APK small.
        // CI also builds x86_64 (-PvpnappAbi=x86_64) purely to run the app in an emulator.
        ndk {
            abiFilters.clear()
            abiFilters.add((project.findProperty("vpnappAbi") as String?) ?: "arm64-v8a")
        }
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
