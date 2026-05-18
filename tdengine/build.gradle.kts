plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("com.taosdata.jdbc:taos-jdbcdriver:3.6.3")
    testImplementation(project(":test-support"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-tdengine")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "TDengine", "Main-Class" to "com.dbx.agent.tdengine.TDengineAgent")
    }
}

tasks.test {
    useJUnitPlatform()
}
