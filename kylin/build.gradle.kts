plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("org.apache.kylin:kylin-jdbc:5.0.0")
    testImplementation(project(":test-support"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-kylin")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "Apache Kylin", "Main-Class" to "com.dbx.agent.kylin.KylinAgentKt")
    }
}

tasks.test {
    useJUnitPlatform()
}
