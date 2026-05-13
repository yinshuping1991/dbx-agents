plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("com.ing.data:cassandra-jdbc-wrapper:4.12.0")
    testImplementation(project(":test-support"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
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

tasks.test {
    useJUnitPlatform()
}
