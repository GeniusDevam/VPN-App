plugins {
    kotlin("jvm") version "2.4.10"
}

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // Android supplies org.json at runtime; the JVM tests need a copy.
    compileOnly("org.json:json:20250517")
    testImplementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

kotlin { jvmToolchain(21) }
tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false }
}

// Used by tools/cert-policy-test.sh: runs the real ConfigSanitizer over profile files (see SanitizeCli.kt).
tasks.register<JavaExec>("sanitizeCli") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.geniusdevam.vpnapp.engine.SanitizeCliKt")
}

// Used by tools/probe-test.sh: runs the real NetworkProber against a real OpenVPN server (see ProbeCli.kt).
tasks.register<JavaExec>("probeCli") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.geniusdevam.vpnapp.engine.ProbeCliKt")
}

// Engine micro-benchmarks for docs/PERFORMANCE.md (see Bench.kt). Not part of `test`.
tasks.register<JavaExec>("benchCli") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.geniusdevam.vpnapp.engine.BenchKt")
    jvmArgs("-Xmx2g")
}
