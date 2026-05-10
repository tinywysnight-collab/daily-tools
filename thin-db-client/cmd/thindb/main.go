package main

import (
	"context"
	"fmt"
	"io"
	"os"
	"time"

	"github.com/tinywang/daily-tools/thin-db-client/internal/cli"
	"github.com/tinywang/daily-tools/thin-db-client/internal/db"
	"github.com/tinywang/daily-tools/thin-db-client/internal/executor"
	"github.com/tinywang/daily-tools/thin-db-client/internal/parser"
)

const (
	exitOK         = 0
	exitConnFailed = 1
	exitSQLError   = 2
	exitFileError  = 3
	exitBadArgs    = 4
)

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

func run(args []string, stdout, stderr io.Writer) int {
	cfg, err := cli.Parse(args)
	if err != nil {
		fmt.Fprintf(stderr, "error: %v\n", err)
		fmt.Fprintln(stderr, "\nUsage: thindb -type <postgres|oracle|mssql> [flags] (-file <path> | -stmt <sql> | <sql>)")
		fmt.Fprintln(stderr, "Flags:")
		fmt.Fprintln(stderr, "  -type    string  Database type: postgres, oracle, mssql")
		fmt.Fprintln(stderr, "  -host    string  Server hostname (default: localhost)")
		fmt.Fprintln(stderr, "  -port    int     Server port (default depends on -type)")
		fmt.Fprintln(stderr, "  -user    string  Username")
		fmt.Fprintln(stderr, "  -pass    string  Password (or env var THINDB_PASS)")
		fmt.Fprintln(stderr, "  -dbname  string  Database/service name")
		fmt.Fprintln(stderr, "  -file    string  SQL script file to execute")
		fmt.Fprintln(stderr, "  -stmt    string  Single SQL statement")
		fmt.Fprintln(stderr, "  -timeout int     Query timeout in seconds (default: 30)")
		fmt.Fprintln(stderr, "  -v               Verbose output")
		return exitBadArgs
	}

	stmts, code := loadStatements(cfg, stderr)
	if code != exitOK {
		return code
	}

	ctx, cancel := context.WithTimeout(context.Background(), cfg.Timeout*time.Duration(len(stmts)+1))
	defer cancel()

	conn, err := db.Open(ctx, cfg)
	if err != nil {
		fmt.Fprintf(stderr, "connection error: %v\n", err)
		return exitConnFailed
	}
	defer conn.Close()

	if err := executor.Execute(ctx, conn, stmts, stdout, cfg.Verbose); err != nil {
		fmt.Fprintf(stderr, "error: %v\n", err)
		return exitSQLError
	}

	return exitOK
}

func loadStatements(cfg cli.Config, stderr io.Writer) ([]string, int) {
	if cfg.File != "" {
		data, err := os.ReadFile(cfg.File)
		if err != nil {
			fmt.Fprintf(stderr, "cannot read file %q: %v\n", cfg.File, err)
			return nil, exitFileError
		}
		stmts := parser.Split(string(data))
		if len(stmts) == 0 {
			fmt.Fprintf(stderr, "file %q contains no SQL statements\n", cfg.File)
			return nil, exitFileError
		}
		return stmts, exitOK
	}

	return []string{cfg.Stmt}, exitOK
}
