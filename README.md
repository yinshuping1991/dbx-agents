# DBX Agents

Agent drivers for [DBX](https://github.com/t8y2/dbx) — domestic database support via JDBC.

Each agent runs as a standalone JVM process and communicates with DBX via stdin/stdout JSON-RPC 2.0.

## Supported Databases

| Agent | Database | JDBC Driver | Protocol |
|-------|----------|-------------|----------|
| dameng | 达梦 DM8 | `dm.jdbc.driver.DmDriver` | Oracle-compatible |
| kingbase | 人大金仓 KingbaseES | `com.kingbase8.Driver` | PostgreSQL-compatible |
| vastbase | Vastbase | `cn.com.vastbase.Driver` | PostgreSQL-compatible |
| goldendb | GoldenDB | `com.mysql.cj.jdbc.Driver` | MySQL-compatible |

## Build

Requires JDK 21.

```bash
./gradlew shadowJar
```

Output JARs are in `{module}/build/libs/`.

JDBC driver JARs (except MySQL Connector/J for GoldenDB) must be obtained from database vendors and placed in `{module}/libs/`.

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
