import java.math.BigDecimal

plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("info.solidsoft.pitest") version "1.15.0"
}

group = "io.axol"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.runelite.net") }
}

dependencies {
    compileOnly("net.runelite:client:1.10.+")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation("net.runelite:client:1.10.+")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    archiveClassifier.set("")
}

tasks.named<Test>("test") {
    useJUnit()
}

pitest {
    pitestVersion.set("1.16.1")
    targetClasses.set(listOf("io.axol.monkwatcher.*"))

    // BridgePlugin is intentionally untested -- its coverage is the live OSRS smoke test
    // documented in CLAUDE.md (no mocks per user prefs). Including it would skew the
    // mutation score with surviving mutants that are unreachable from JUnit.
    // BridgeConfig is an interface with a single default method that reads an env var;
    // no behavioral test exists for it.
    excludedClasses.set(listOf(
        "io.axol.monkwatcher.BridgePlugin",
        "io.axol.monkwatcher.BridgeConfig"
    ))

    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)

    // Observed 74% on the first real run with 17/23 mutations killed. Remaining 6
    // survivors are equivalent mutants (writer-thread setDaemon shadowed by parent's
    // daemon flag, stop()-time interrupt shadowed by close()) or untested recovery
    // paths in the accept-IOException catch block. The load-bearing architectural
    // invariants (offer-not-put, outbox.clear on accept, bounded queue, Jackson
    // serialization, channel close on stop) all kill. 70 gives a small buffer for
    // line-coverage drift without being a rubber stamp.
    mutationThreshold.set(70)
    coverageThreshold.set(85)

    // Property tests + UDS round-trips run longer than typical unit tests. Give PIT
    // headroom so it doesn't flag slow tests as timed-out mutations.
    timeoutFactor.set(BigDecimal("2.0"))
    timeoutConstInMillis.set(10000)
}
