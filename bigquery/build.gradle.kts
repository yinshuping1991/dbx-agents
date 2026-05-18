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
    archiveBaseName.set("dbx-agent-bigquery")
    archiveClassifier.set("")
    manifest {
        attributes(
            "Agent-Label" to "Google BigQuery",
            "Agent-External-Driver" to "true",
            "Main-Class" to "com.dbx.agent.bigquery.BigQueryAgent"
        )
    }
}

tasks.test {
    useJUnitPlatform()
}
