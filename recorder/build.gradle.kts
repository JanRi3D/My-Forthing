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
