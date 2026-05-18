plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation("com.yashandb:yashandb-jdbc:1.9.27")
    testImplementation(project(":test-support"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-yashandb")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "崖山 YashanDB", "Main-Class" to "com.dbx.agent.yashandb.YashandbAgent")
    }
}

tasks.test {
    useJUnitPlatform()
}
