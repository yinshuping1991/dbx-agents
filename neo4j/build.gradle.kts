plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("org.neo4j:neo4j-jdbc-full-bundle:6.10.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-neo4j")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "Neo4j", "Main-Class" to "com.dbx.agent.neo4j.Neo4jAgentKt")
    }
}
