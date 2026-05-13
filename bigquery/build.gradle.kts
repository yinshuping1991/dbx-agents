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
    archiveBaseName.set("dbx-agent-bigquery")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "Google BigQuery", "Main-Class" to "com.dbx.agent.bigquery.BigQueryAgentKt")
    }
}
