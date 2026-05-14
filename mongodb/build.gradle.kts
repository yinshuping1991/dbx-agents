plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation("org.mongodb:mongodb-driver-sync:4.11.4")
    implementation("com.google.code.gson:gson:2.11.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-mongodb")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "MongoDB (Legacy)", "Main-Class" to "com.dbx.agent.mongodb.MongoAgentKt")
    }
}

tasks.test {
    useJUnitPlatform()
}
