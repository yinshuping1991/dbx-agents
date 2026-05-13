plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-sundb")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "SunDB", "Main-Class" to "com.dbx.agent.sundb.SundbAgentKt")
    }
}
