package output

import (
	"database/sql"
	"fmt"
	"io"
	"text/tabwriter"
)

// WriteTable renders sql.Rows as an ASCII table to w.
// It prints column headers, one data row per line, and a row count footer.
func WriteTable(w io.Writer, rows *sql.Rows) error {
	cols, err := rows.Columns()
	if err != nil {
		return fmt.Errorf("get columns: %w", err)
	}

	tw := tabwriter.NewWriter(w, 0, 0, 2, ' ', 0)

	// Header row.
	for i, col := range cols {
		if i > 0 {
			fmt.Fprint(tw, "\t")
		}
		fmt.Fprint(tw, col)
	}
	fmt.Fprintln(tw)

	// Data rows.
	vals := make([]any, len(cols))
	ptrs := make([]any, len(cols))
	for i := range vals {
		ptrs[i] = &vals[i]
	}

	var count int
	for rows.Next() {
		if err := rows.Scan(ptrs...); err != nil {
			return fmt.Errorf("scan row: %w", err)
		}
		for i, v := range vals {
			if i > 0 {
				fmt.Fprint(tw, "\t")
			}
			fmt.Fprint(tw, formatValue(v))
		}
		fmt.Fprintln(tw)
		count++
	}
	if err := rows.Err(); err != nil {
		return fmt.Errorf("iterate rows: %w", err)
	}

	tw.Flush()
	fmt.Fprintf(w, "(%d row", count)
	if count != 1 {
		fmt.Fprint(w, "s")
	}
	fmt.Fprintln(w, ")")
	return nil
}

func formatValue(v any) string {
	if v == nil {
		return "NULL"
	}
	return fmt.Sprintf("%v", v)
}
