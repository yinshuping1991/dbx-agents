plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation("io.github.spannm:ucanaccess:5.1.5")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(project(":test-support"))
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-access")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "Microsoft Access", "Main-Class" to "com.dbx.agent.access.AccessAgent")
    }
}

tasks.test {
    useJUnitPlatform()
}
