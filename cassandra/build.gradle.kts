plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("com.ing.data:cassandra-jdbc-wrapper:4.12.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-cassandra")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "Apache Cassandra", "Main-Class" to "com.dbx.agent.cassandra.CassandraAgentKt")
    }
}
