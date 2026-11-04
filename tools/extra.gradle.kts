// VPNAPP_EXTRA_BEGIN  (appended to ics-openvpn main/build.gradle.kts by tools/prepare.sh)
android {
    defaultConfig {
        applicationId = "io.github.geniusdevam.vpnapp"
        // Phones running Android 16 are arm64; building one ABI keeps CI fast and the APK small.
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
    }
}
// VPNAPP_EXTRA_END
