plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("cn.com.vastdata:vastbase-jdbc:2.11v")
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
