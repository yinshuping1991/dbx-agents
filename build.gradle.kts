plugins {
    kotlin("jvm") version "2.1.0" apply false
    id("com.gradleup.shadow") version "9.0.0-beta12" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}
