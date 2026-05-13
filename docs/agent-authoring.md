# Agent Authoring Guide

This guide defines the expected shape of a DBX agent. Treat it as the checklist for adding or reviewing an agent.

## Agent Contract

Every agent is a standalone JVM process that:

- Implements `com.dbx.agent.DatabaseAgent`.
- Starts with `JsonRpcServer(<Agent>()).run()` in its `main` function.
- Talks to DBX over stdin/stdout JSON-RPC 2.0.
- Uses JDBC for database access unless the module is explicitly designed around a non-JDBC protocol.
- Produces one shadow JAR named `dbx-agent-<agent-name>.jar`.

The public behavior should be consistent across agents even when each database has different SQL dialects.

## Required Methods

Each agent must implement these capabilities:

- `connect(params)`: load the driver class, build the JDBC URL, open one `Connection`, and store enough context for metadata calls.
- `testConnection(params)`: open a short-lived connection and close it with `use`.
- `listDatabases()`: return visible catalogs/databases when the database supports them; otherwise return one sensible default database.
- `listSchemas()`: return schemas in stable order.
- `listTables(schema)`: return table-like objects for the selected schema, with normalized `type` values where possible.
- `getColumns(schema, table)`: return column metadata, nullability, defaults, key flags, numeric precision/scale, character length, and comments when available.
- `listIndexes(schema, table)`: return one `IndexInfo` per index, preserving column order.
- `listForeignKeys(schema, table)`: return outbound foreign keys when available.
- `listTriggers(schema, table)`: return triggers when available; return an empty list if the database has no trigger metadata.
- `executeQuery(sql, schema)`: execute arbitrary user SQL through `JdbcExecutor`.
- `disconnect()`: close the connection and clear the stored reference.
- `getConnection()`: return the stored `Connection?`.

Throw `IllegalStateException("Not connected")` when a method requires a connection and none exists.

## SQL Execution Rules

Do not classify statements by SQL prefix inside individual agents.

Use:

```kotlin
override fun executeQuery(sql: String, schema: String?): QueryResult {
    return JdbcExecutor.execute(requireConnection(), sql, schema, ::setSchemaSQL)
}
```

Some drivers return non-standard Java types from `ResultSet.getObject`. If a driver is safer when values are stringified, pass a value reader:

```kotlin
override fun executeQuery(sql: String, schema: String?): QueryResult {
    return JdbcExecutor.execute(
        requireConnection(),
        sql,
        schema,
        ::setSchemaSQL,
        valueReader = ::stringResultValue,
    )
}

private fun stringResultValue(rs: ResultSet, index: Int, sqlType: Int): Any? {
    return rs.getString(index)
}
```

`JdbcExecutor` owns these behaviors:

- Trims a trailing semicolon before execution.
- Handles `BEGIN`, `COMMIT`, and `ROLLBACK`.
- Runs schema switching SQL before the user statement.
- Executes statements with `Statement.execute(...)`.
- Reads `ResultSet` output for any statement type, not only `SELECT`.
- Returns update counts for update statements.
- Caps result rows at `JdbcExecutor.DEFAULT_MAX_ROWS`.
- Marks `truncated = true` only when more rows exist beyond the cap.

An agent must not reintroduce local copies of:

- `QUERY_PREFIXES`
- `MAX_ROWS`
- `executeUpdate(trimmedSql)` in `executeQuery`
- result truncation based on `rows.size >= MAX_ROWS`

## Schema And Identifier Rules

Override `setSchemaSQL(schema)` for the database dialect.

Use `JdbcIdentifiers` helpers when quoting identifiers:

```kotlin
override fun setSchemaSQL(schema: String): String {
    return "SET SCHEMA ${JdbcIdentifiers.doubleQuote(schema)}"
}
```

If the database does not support a schema switching statement, return an empty string:

```kotlin
override fun setSchemaSQL(schema: String): String = ""
```

Never concatenate unquoted user-provided schema names into schema-switching SQL unless the target database requires unquoted identifiers and the value has already been validated.

For metadata queries, prefer prepared statements for `schema`, `table`, and other user-controlled values.

## Driver Packaging

Each module chooses one of two driver modes.

### Bundled Driver

Use this when the driver is redistributable from Maven Central or another permitted repository:

```kotlin
dependencies {
    implementation(project(":common"))
    implementation("com.example:example-jdbc:1.2.3")
}
```

No manifest flag is needed.

### External Driver

Use this when the driver cannot be redistributed or must be supplied by the user:

```kotlin
dependencies {
    implementation(project(":common"))
    implementation(fileTree("libs") { include("*.jar") })
}

tasks.shadowJar {
    manifest {
        attributes(
            "Agent-Label" to "Example DB",
            "Agent-External-Driver" to "true",
            "Main-Class" to "com.dbx.agent.example.ExampleAgentKt",
        )
    }
}
```

The release workflow reads `Agent-External-Driver: true` and emits `external_driver_required: true` in `agent-registry.json`.

## Module Registration

When adding an agent named `exampledb`:

- Add `include("exampledb")` to `settings.gradle.kts`.
- Add `"exampledb": "0.1.0"` to `versions.json`.
- Set `archiveBaseName` to `dbx-agent-exampledb`.
- Set `Agent-Label` to the user-facing database name.
- Set `Main-Class` to the Kotlin generated main class, usually `com.dbx.agent.exampledb.ExampledbAgentKt`.
- Add the database to the README support table.

`versions.json` must contain only modules included in `settings.gradle.kts`, excluding infrastructure modules such as `common` and `test-support`.

## Runtime Selection

Default to:

```kotlin
kotlin {
    jvmToolchain(21)
}
```

Only use JRE 8 for drivers that require it, such as legacy Oracle 10g support. If an agent needs a special runtime, update the release workflow JRE detection logic and document the reason in the module.

## Tests

Every JDBC agent should have at least one execution-path regression test.

For agents that can run with an embedded or in-memory database, prefer both shared behavior contracts:

```kotlin
class H2ExecutionBehaviorTest : JdbcExecutionBehaviorTest() {
    override fun createConnectedAgent(databaseName: String): DatabaseAgent {
        return createH2Agent(databaseName)
    }

    override fun resultSetSql(): String = "CALL 42"
    override fun expectedResultSetColumns(): List<String> = listOf("42")
    override fun expectedResultSetRows(): List<List<Any?>> = listOf(listOf(42))
    override fun rowsSql(rowCount: Int): String = "SELECT X FROM SYSTEM_RANGE(1, $rowCount)"
}

class H2MetadataBehaviorTest : JdbcMetadataBehaviorTest() {
    override fun createConnectedAgent(databaseName: String): DatabaseAgent {
        return createH2Agent(databaseName)
    }

    override fun metadataFixtureSql(): List<String> = listOf(
        "CREATE TABLE BETA_TABLE (ID INT PRIMARY KEY)",
        "CREATE TABLE ALPHA_TABLE (ID INT PRIMARY KEY)",
        "CREATE TABLE COLUMN_ORDER_SAMPLE (ID INT PRIMARY KEY, NAME VARCHAR(64), CREATED_AT TIMESTAMP)",
    )

    override fun metadataSchema(): String = "PUBLIC"
    override fun expectedTablesInOrder(): List<String> = listOf("ALPHA_TABLE", "BETA_TABLE", "COLUMN_ORDER_SAMPLE")
    override fun metadataColumnsTable(): String = "COLUMN_ORDER_SAMPLE"
    override fun expectedColumnsInOrder(): List<String> = listOf("ID", "NAME", "CREATED_AT")
}

private fun createH2Agent(databaseName: String): DatabaseAgent {
    return H2Agent().apply {
        connect(ConnectParams(database = "mem:$databaseName;DB_CLOSE_DELAY=-1"))
    }
}
```

`JdbcExecutionBehaviorTest` verifies non-`SELECT` result set execution, max-row truncation boundaries, and transaction control statements. `JdbcMetadataBehaviorTest` verifies stable metadata ordering. Agents with limited local test infrastructure can adopt the execution contract first and add the metadata contract later.

For agents that need unavailable commercial or external drivers, use `test-support`:

```kotlin
@Test
fun `executeQuery uses Statement execute`() {
    val agent = ExampleAgent()
    setPrivateConnection(agent, JdbcAgentFake.connection())

    agent.executeQuery("SHOW TABLES", null)

    assertEquals(listOf("execute"), JdbcAgentFake.calls)
}
```

Use `testImplementation(project(":test-support"))` when the test needs `JdbcAgentFake`.

## Review Checklist

Before opening a PR or release:

- `executeQuery` delegates to `JdbcExecutor.execute`.
- No local SQL-prefix classifier exists.
- No local result row cap exists unless there is a database-specific reason documented in code.
- Metadata methods use prepared statements for user-controlled schema/table inputs.
- `setSchemaSQL` quotes identifiers or returns an empty string.
- `disconnect` closes and clears the connection.
- `testConnection` closes its temporary connection.
- `build.gradle.kts` has correct `archiveBaseName`, `Agent-Label`, `Main-Class`, dependencies, and external driver flag.
- `settings.gradle.kts`, `versions.json`, and README are updated together.
- At least one execution-path regression test exists.
- `python3 scripts/validate_agents.py` passes.
- `./gradlew test shadowJar --continue` passes.
