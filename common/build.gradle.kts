plugins {
    kotlin("jvm")
}

dependencies {
    implementation("com.google.code.gson:gson:2.12.1")
}

kotlin {
    jvmToolchain(21)
}
