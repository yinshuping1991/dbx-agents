plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    testImplementation(project(":test-support"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-sundb")
    archiveClassifier.set("")
    manifest {
        attributes(
            "Agent-Label" to "SunDB",
            "Agent-External-Driver" to "true",
            "Main-Class" to "com.dbx.agent.sundb.SundbAgentKt"
        )
    }
}

tasks.test {
    useJUnitPlatform()
}
