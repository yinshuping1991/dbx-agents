plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("org.apache.hive:hive-jdbc:4.0.1")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-hive")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "Apache Hive", "Main-Class" to "com.dbx.agent.hive.HiveAgentKt")
    }
}
