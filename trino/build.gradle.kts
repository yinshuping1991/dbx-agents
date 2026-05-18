plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("io.trino:trino-jdbc:471")
    testImplementation(project(":test-support"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-trino")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "Trino (Presto)", "Main-Class" to "com.dbx.agent.trino.TrinoAgent")
    }
}

tasks.test {
    useJUnitPlatform()
}
