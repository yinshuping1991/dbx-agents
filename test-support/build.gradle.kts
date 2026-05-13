plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(kotlin("test"))
    api("org.junit.jupiter:junit-jupiter-api:5.11.4")
    implementation(project(":common"))
}

kotlin {
    jvmToolchain(8)
}
