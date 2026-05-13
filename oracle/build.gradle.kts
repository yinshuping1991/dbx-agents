plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("com.oracle.database.jdbc:ojdbc11:23.7.0.25.01")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-oracle")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "Oracle", "Main-Class" to "com.dbx.agent.oracle.OracleAgentKt")
    }
}
