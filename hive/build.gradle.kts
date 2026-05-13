plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("org.apache.hive:hive-jdbc:4.0.1:standalone")
    implementation("org.slf4j:slf4j-nop:1.7.30")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
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

tasks.test {
    useJUnitPlatform()
}
