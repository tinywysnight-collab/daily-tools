package executor

import (
	"context"
	"database/sql"
	"fmt"
	"io"
	"strings"

	"github.com/tinywang/daily-tools/thin-db-client/internal/output"
)

// Execute runs each SQL statement in stmts against db.
// It stops on the first error (fail-fast). When verbose is true, each
// statement is printed to w before execution.
func Execute(ctx context.Context, db *sql.DB, stmts []string, w io.Writer, verbose bool) error {
	for _, stmt := range stmts {
		if verbose {
			fmt.Fprintf(w, "-- %s\n", stmt)
		}

		var execErr error
		if isQuery(stmt) {
			execErr = runQuery(ctx, db, stmt, w)
		} else {
			execErr = runExec(ctx, db, stmt, w)
		}

		if execErr != nil {
			return fmt.Errorf("statement failed: %w\nSQL: %s", execErr, stmt)
		}
	}
	return nil
}

// isQuery returns true when the statement is expected to return rows.
func isQuery(stmt string) bool {
	upper := strings.ToUpper(strings.TrimSpace(stmt))
	queryKeywords := []string{"SELECT", "WITH", "SHOW", "EXPLAIN", "DESCRIBE", "DESC"}
	for _, kw := range queryKeywords {
		if strings.HasPrefix(upper, kw) {
			return true
		}
	}
	return false
}

func runQuery(ctx context.Context, db *sql.DB, stmt string, w io.Writer) error {
	rows, err := db.QueryContext(ctx, stmt)
	if err != nil {
		return err
	}
	defer rows.Close()

	return output.WriteTable(w, rows)
}

func runExec(ctx context.Context, db *sql.DB, stmt string, w io.Writer) error {
	result, err := db.ExecContext(ctx, stmt)
	if err != nil {
		return err
	}

	affected, err := result.RowsAffected()
	if err != nil {
		// Some drivers (Oracle) don't support RowsAffected — silently ignore.
		fmt.Fprintln(w, "OK")
		return nil
	}
	fmt.Fprintf(w, "(%d row", affected)
	if affected != 1 {
		fmt.Fprint(w, "s")
	}
	fmt.Fprintln(w, " affected)")
	return nil
}
