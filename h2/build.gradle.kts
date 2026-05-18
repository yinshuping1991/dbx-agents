plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("com.h2database:h2:2.3.232")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(project(":test-support"))
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-h2")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "H2", "Main-Class" to "com.dbx.agent.h2.H2Agent")
    }
}

tasks.test {
    useJUnitPlatform()
}
