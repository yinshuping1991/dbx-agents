plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("cn.com.kingbase:kingbase8:9.0.1.jre7")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-kingbase")
    archiveClassifier.set("")
    manifest {
        attributes("Main-Class" to "com.dbx.agent.kingbase.KingbaseAgentKt")
    }
}
