import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-independent Nostr and crypto logic. Kept free of Android APIs so it
// can be unit tested on a plain JVM.
plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("fr.acinq.secp256k1:secp256k1-kmp:0.24.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // Android ships org.json; the JVM needs it for compiling and tests.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
}

// The JVM JNI loader used only by tests is published for Java 21+.
configurations.testRuntimeClasspath {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}
