plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("cn.com.kingbase:kingbase8:9.0.1.jre7")
    testImplementation(project(":test-support"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-kingbase")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "人大金仓 KingbaseES", "Main-Class" to "com.dbx.agent.kingbase.KingbaseAgentKt")
    }
}

tasks.test {
    useJUnitPlatform()
}
