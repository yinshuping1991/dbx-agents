plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("cn.com.vastdata:vastbase-jdbc:2.11v")
    testImplementation(project(":test-support"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-vastbase")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "Vastbase", "Main-Class" to "com.dbx.agent.vastbase.VastbaseAgentKt")
    }
}

tasks.test {
    useJUnitPlatform()
}
