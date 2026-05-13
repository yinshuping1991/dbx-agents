plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("net.snowflake:snowflake-jdbc:3.22.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("dbx-agent-snowflake")
    archiveClassifier.set("")
    manifest {
        attributes("Agent-Label" to "Snowflake", "Main-Class" to "com.dbx.agent.snowflake.SnowflakeAgentKt")
    }
}
