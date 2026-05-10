package output

import (
	"database/sql/driver"
	"strings"
	"testing"

	sqlmock "github.com/DATA-DOG/go-sqlmock"
	"github.com/stretchr/testify/require"
)

func TestWriteTable(t *testing.T) {
	tests := []struct {
		name       string
		columns    []string
		rows       [][]any
		wantLines  []string
		wantFooter string
	}{
		{
			name:    "single row",
			columns: []string{"id", "name"},
			rows:    [][]any{{1, "alice"}},
			wantLines: []string{
				"id  name",
				"1   alice",
			},
			wantFooter: "(1 row)",
		},
		{
			name:    "multiple rows",
			columns: []string{"id", "name"},
			rows: [][]any{
				{1, "alice"},
				{2, "bob"},
			},
			wantLines: []string{
				"id  name",
				"1   alice",
				"2   bob",
			},
			wantFooter: "(2 rows)",
		},
		{
			name:       "empty result set",
			columns:    []string{"id", "name"},
			rows:       nil,
			wantLines:  []string{"id  name"},
			wantFooter: "(0 rows)",
		},
		{
			name:    "null value",
			columns: []string{"id", "email"},
			rows:    [][]any{{1, nil}},
			wantLines: []string{
				"id  email",
				"1   NULL",
			},
			wantFooter: "(1 row)",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			db, mock, err := sqlmock.New()
			require.NoError(t, err)
			defer db.Close()

			expectedRows := sqlmock.NewRows(tc.columns)
			for _, row := range tc.rows {
				dvals := make([]driver.Value, len(row))
				for i, v := range row {
					dvals[i] = v
				}
				expectedRows.AddRow(dvals...)
			}
			mock.ExpectQuery("SELECT").WillReturnRows(expectedRows)

			sqlRows, err := db.Query("SELECT")
			require.NoError(t, err)
			defer sqlRows.Close()

			var buf strings.Builder
			err = WriteTable(&buf, sqlRows)
			require.NoError(t, err)

			output := buf.String()
			for _, line := range tc.wantLines {
				require.Contains(t, output, line)
			}
			require.Contains(t, output, tc.wantFooter)
		})
	}
}
