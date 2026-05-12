plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("com.dameng:DmJdbcDriver18:8.1.3.140")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-dameng")
    archiveClassifier.set("")
    manifest {
        attributes("Main-Class" to "com.dbx.agent.dameng.DamengAgentKt")
    }
}
