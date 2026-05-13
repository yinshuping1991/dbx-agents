plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("com.oracle.database.jdbc:ojdbc8:19.26.0.0")
}

kotlin {
    jvmToolchain(8)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-oracle-10g")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "Oracle 10g", "Main-Class" to "com.dbx.agent.oracle10g.Oracle10gAgentKt")
    }
}
