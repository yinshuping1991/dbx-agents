plugins {
    kotlin("jvm")
}

dependencies {
    implementation("com.google.code.gson:gson:2.12.1")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

kotlin {
    jvmToolchain(8)
}

tasks.test {
    useJUnitPlatform()
}
