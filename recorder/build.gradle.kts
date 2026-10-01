import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure JVM module: Forthing U-Tour recorder control protocol. No Android dependencies.
// RecorderSimulator lives in testFixtures; consumers use testImplementation(testFixtures(project(":recorder"))).
plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Same bytecode level as :app's compileOptions.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_11 }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
}

tasks.test {
    useJUnit()
}

// Debug simulator for the app's "Simulator (10.0.2.2:7878)" mode. The server reads dashcam.rsaKey from
// local.properties itself, so the key never passes through Gradle (or its configuration cache).
tasks.register<JavaExec>("runSimulator") {
    group = "application"
    description = "Runs the recorder simulator on 127.0.0.1:7878 and its media HTTP server on 8080 (emulator: 10.0.2.2)."
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass = "me.ri3d.cam.recorder.SimulatorTcpServerKt"
    // -PsimThrottle=<bytes per second> slows the media downloads (resume tests).
    args(rootProject.file("local.properties").absolutePath, "7878", "8080", providers.gradleProperty("simThrottle").getOrElse("0"))
}
