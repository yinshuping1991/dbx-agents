# DBX Agents

Agent drivers for [DBX](https://github.com/t8y2/dbx) — database support via JDBC.

Each agent runs as a standalone JVM process and communicates with DBX via stdin/stdout JSON-RPC 2.0.

## Supported Databases

| Agent | Database | JDBC Driver |
|-------|----------|-------------|
| dameng | 达梦 DM8 | DM JDBC |
| kingbase | 人大金仓 KingbaseES | KingbaseES JDBC |
| vastbase | Vastbase | Vastbase JDBC |
| goldendb | GoldenDB | MySQL Connector/J |
| oracle | Oracle (11g+) | ojdbc11 |
| oracle-10g | Oracle 10g | ojdbc8 (JRE 8) |
| h2 | H2 | H2 JDBC |
| snowflake | Snowflake | Snowflake JDBC |
| trino | Trino (Presto) | Trino JDBC |
| hive | Apache Hive | Hive JDBC |
| db2 | IBM DB2 | DB2 JDBC |
| informix | IBM Informix | Informix JDBC |
| neo4j | Neo4j | Neo4j JDBC |
| cassandra | Apache Cassandra | Cassandra JDBC |
| bigquery | Google BigQuery | BigQuery JDBC |
| kylin | Apache Kylin | Kylin JDBC |
| sundb | SunDB | SunDB JDBC |
| gaussdb | GaussDB | GaussDB JDBC |

## Multi-JRE Support

Most agents target JRE 17. Agents that require legacy Java runtimes (e.g. `oracle-10g` uses JRE 8) declare their JRE version in the registry. DBX downloads and manages multiple JRE installations automatically.

## Build

Requires JDK 8 and 21 (Gradle toolchain auto-downloads if needed).

```bash
./gradlew shadowJar
```

Output JARs are in `{module}/build/libs/`.

## Development

- Agent authoring guide: [docs/agent-authoring.md](docs/agent-authoring.md)
- JDBC agent template: [docs/examples/jdbc-agent-template](docs/examples/jdbc-agent-template)

## Architecture

```
DBX Main Process (Rust/Tauri)
    │ stdin/stdout (JSON-RPC 2.0)
    ▼
java -jar dbx-agent-{type}.jar
    │
    ▼
JDBC → Database
```

## License

[AGPL-3.0](https://github.com/t8y2/dbx/blob/main/LICENSE)
