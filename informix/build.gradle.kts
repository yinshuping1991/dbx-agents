plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("com.ibm.informix:jdbc:4.50.10")
    testImplementation(project(":test-support"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-informix")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "IBM Informix", "Main-Class" to "com.dbx.agent.informix.InformixAgentKt")
    }
}

tasks.test {
    useJUnitPlatform()
}
