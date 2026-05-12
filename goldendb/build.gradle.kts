plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("com.mysql:mysql-connector-j:9.2.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-goldendb")
    archiveClassifier.set("")
    manifest {
        attributes("Main-Class" to "com.dbx.agent.goldendb.GoldendbAgentKt")
    }
}
