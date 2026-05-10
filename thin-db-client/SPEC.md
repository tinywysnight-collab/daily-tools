# thin-db-client Specification

## Overview

A cross-platform CLI tool for executing SQL statements and scripts against PostgreSQL, Oracle, and MS SQL Server. Designed for database initialization tasks: user creation, permission control, password changes, and basic queries.

---

## CLI Interface

```
thindb -type <postgres|oracle|mssql> [connection flags] (-file <path> | -stmt <sql> | <sql>)
```

### Connection Flags

| Flag | Type | Default | Description |
|------|------|---------|-------------|
| `-type` | string | — | **Required.** Database type: `postgres`, `oracle`, `mssql` |
| `-host` | string | `localhost` | Server hostname |
| `-port` | int | (per type) | Port: postgres=5432, oracle=1521, mssql=1433 |
| `-user` | string | — | **Required.** Username |
| `-pass` | string | — | Password. Prefer env var `THINDB_PASS` to avoid shell history leakage |
| `-dbname` | string | — | **Required.** Database name (Postgres), service name (Oracle), or database (MSSQL) |

### Execution Flags (mutually exclusive)

| Flag | Description |
|------|-------------|
| `-file <path>` | Execute all statements in a SQL script file |
| `-stmt <sql>` | Execute a single SQL statement |
| `<sql>` (positional) | Fallback: treated as `-stmt` when neither flag is set |

### Optional Flags

| Flag | Default | Description |
|------|---------|-------------|
| `-timeout` | `30` | Query timeout in seconds |
| `-v` | false | Verbose: print each statement before executing |

### Exit Codes

| Code | Meaning |
|------|---------|
| 0 | All statements executed successfully |
| 1 | Connection failed |
| 2 | SQL execution error |
| 3 | File not found or unreadable |
| 4 | Invalid flags or missing required arguments |

### Examples

```bash
# Execute an init SQL script against PostgreSQL
thindb -type postgres -host db.corp.com -user admin -pass "$PW" -dbname mydb -file ./init.sql

# Create a user on Oracle
thindb -type oracle -host oracle.corp.com -user sys -dbname ORCLPDB1 \
  -stmt "CREATE USER app IDENTIFIED BY secret"

# Change password on MS SQL Server
thindb -type mssql -host sql.corp.com -user sa -dbname master \
  -stmt "ALTER LOGIN appuser WITH PASSWORD = 'newpass'"

# Quick query using positional arg and env-var password
THINDB_PASS=secret thindb -type postgres -host localhost -user app -dbname mydb "SELECT version()"
```

---

## Project Structure

```
thin-db-client/
├── SPEC.md
├── AGENTS.md
├── CLAUDE.md
├── GUIDELINE.md
├── go.mod
├── go.sum
├── Makefile
├── bin/                          # git-ignored build output
├── cmd/
│   └── thindb/
│       └── main.go              # Thin entrypoint: parse flags, wire, os.Exit
└── internal/
    ├── cli/
    │   └── flags.go             # Config struct, Parse(), validation
    ├── db/
    │   ├── driver.go            # Open() factory via database/sql
    │   ├── postgres.go          # Postgres DSN builder
    │   ├── oracle.go            # Oracle DSN builder
    │   └── mssql.go             # MSSQL DSN builder
    ├── executor/
    │   └── executor.go          # Execution loop, result printing
    ├── parser/
    │   └── sql.go               # Multi-statement SQL splitter
    └── output/
        └── table.go             # ASCII table renderer
```

---

## Dependencies

| Package | Purpose |
|---------|---------|
| `github.com/lib/pq` | PostgreSQL driver |
| `github.com/sijms/go-ora/v2` | Oracle driver — pure Go, no Oracle Client required |
| `github.com/microsoft/go-mssqldb` | MS SQL Server driver |
| `github.com/stretchr/testify` | Test assertions |
| `github.com/DATA-DOG/go-sqlmock` | SQL mock for unit tests (test-only) |

All drivers implement the standard `database/sql` interface. No CGO — enables clean cross-platform cross-compilation.

---

## Package Responsibilities

### `internal/cli`
Owns the `Config` struct and all flag parsing. `Parse(args []string) (Config, error)` is the only public entry point. Reads `THINDB_PASS` env var and merges with `-pass` flag (env var takes precedence). Validates that exactly one execution mode is set and all required flags are present.

### `internal/parser`
`Split(script string) []string` takes raw SQL file content and returns individual statements. Splitting strategy:
1. Split on lines containing only `GO` (case-insensitive) — handles MS SQL Server batch separators.
2. Split each chunk on `;`.
3. Trim whitespace and filter blank results.

**Known limitation**: PL/SQL `BEGIN ... END;` blocks containing interior semicolons will be incorrectly split. This is acceptable for the stated use cases (DDL, grants, simple queries).

### `internal/db`
`Open(ctx context.Context, cfg cli.Config) (*sql.DB, error)` builds the driver-specific DSN and calls `sql.Open` followed by `db.PingContext`. Each database type has its own DSN builder function in a separate file.

### `internal/executor`
`Execute(ctx context.Context, db *sql.DB, stmts []string, w io.Writer, verbose bool) error` loops over statements and for each:
1. If verbose, prints the statement to `w`.
2. Detects query vs. exec by inspecting the first keyword (`SELECT`, `WITH`, `SHOW`, `EXPLAIN` → `QueryContext`; otherwise `ExecContext`).
3. For queries: prints results via `output.WriteTable`.
4. For DML/DDL: prints rows affected.
5. Returns immediately on first error (fail-fast).

### `internal/output`
`WriteTable(w io.Writer, rows *sql.Rows) error` renders query results as an ASCII table using `text/tabwriter` from the standard library.

Example:
```
id   name    email
1    alice   alice@example.com
2    bob     bob@example.com
(2 rows)
```

### `cmd/thindb/main.go`
Thin wiring only. Separates `main()` from `run(args, stdout, stderr) int` to enable unit testing without subprocess overhead.

---

## Cross-Platform Build

```makefile
release:
    GOOS=linux   GOARCH=amd64  go build -o bin/thindb-linux-amd64       ./cmd/thindb
    GOOS=darwin  GOARCH=amd64  go build -o bin/thindb-darwin-amd64      ./cmd/thindb
    GOOS=darwin  GOARCH=arm64  go build -o bin/thindb-darwin-arm64      ./cmd/thindb
    GOOS=windows GOARCH=amd64  go build -o bin/thindb-windows-amd64.exe ./cmd/thindb
```

All drivers are pure Go, so no additional toolchain is required for cross-compilation.

---

## Testing Strategy

| Package | Test Type | Notes |
|---------|-----------|-------|
| `internal/cli` | Unit | stdlib only; covers all flag combinations and env var |
| `internal/parser` | Unit, table-driven | semicolons, GO separator, blank filtering |
| `internal/output` | Unit | column rendering, empty result set |
| `internal/db` | Unit (DSN) + Integration | DSN tests are pure; integration tests skip when env vars unset |
| `internal/executor` | Unit with go-sqlmock | fail-fast, verbose output, query vs exec routing |
| `cmd/thindb` | Integration (env-gated) | end-to-end with real DB |

Integration tests are skipped when `TEST_PG_DSN`, `TEST_ORACLE_DSN`, or `TEST_MSSQL_DSN` are not set. Coverage target: ≥80% on all `internal/` packages.

---

## Implementation Order

1. `go mod init` — initialize module
2. `internal/cli/flags.go` + tests
3. `internal/parser/sql.go` + tests
4. `internal/output/table.go` + tests
5. Add driver dependencies via `go get`
6. `internal/db/` (DSN builders + Open factory) + tests
7. `internal/executor/executor.go` + tests (with go-sqlmock)
8. `cmd/thindb/main.go` + env-gated integration test
9. `Makefile` with build, test, lint, and release targets

---

## Verification

```bash
make build    # produces bin/thindb
make test     # go test -v -race -cover ./...
make lint     # golangci-lint run ./...
make release  # verify all 4 cross-platform targets compile
```

End-to-end smoke test (requires local Postgres):
```bash
THINDB_PASS=postgres thindb -type postgres -host localhost -user postgres -dbname postgres "SELECT version()"
```
Expected: ASCII table with version string, exit code 0.
