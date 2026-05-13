plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("org.postgresql:postgresql:42.7.5")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-gaussdb")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "GaussDB", "Main-Class" to "com.dbx.agent.gaussdb.GaussdbAgentKt")
    }
}
