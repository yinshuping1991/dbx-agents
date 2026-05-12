plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-vastbase")
    archiveClassifier.set("")
    manifest {
        attributes("Main-Class" to "com.dbx.agent.vastbase.MainKt")
    }
}
