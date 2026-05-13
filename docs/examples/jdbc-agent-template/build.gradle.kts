plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))

    // Bundled driver example:
    // implementation("com.example:example-jdbc:1.2.3")

    // External driver example:
    // implementation(fileTree("libs") { include("*.jar") })

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(project(":test-support"))
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-template")
    archiveClassifier.set("")
    manifest {
        attributes(
            "Agent-Label" to "Template DB",
            // Add this only when users must provide the JDBC driver jar:
            // "Agent-External-Driver" to "true",
            "Main-Class" to "com.dbx.agent.template.TemplateAgentKt",
        )
    }
}

tasks.test {
    useJUnitPlatform()
}
