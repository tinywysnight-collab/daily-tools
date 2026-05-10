package parser

import (
	"testing"

	"github.com/stretchr/testify/require"
)

func TestSplit(t *testing.T) {
	tests := []struct {
		name   string
		input  string
		want   []string
	}{
		{
			name:  "single statement without semicolon",
			input: "SELECT 1",
			want:  []string{"SELECT 1"},
		},
		{
			name:  "single statement with semicolon",
			input: "SELECT 1;",
			want:  []string{"SELECT 1"},
		},
		{
			name:  "multiple semicolon-separated statements",
			input: "CREATE USER app;\nGRANT SELECT ON mydb.* TO app;\nFLUSH PRIVILEGES;",
			want: []string{
				"CREATE USER app",
				"GRANT SELECT ON mydb.* TO app",
				"FLUSH PRIVILEGES",
			},
		},
		{
			name:  "blank lines and extra whitespace are filtered",
			input: "  SELECT 1  ;\n\n  SELECT 2  ;  \n",
			want:  []string{"SELECT 1", "SELECT 2"},
		},
		{
			name:  "GO batch separator (MS SQL style)",
			input: "CREATE LOGIN appuser WITH PASSWORD='abc123'\nGO\nSELECT @@version\nGO",
			want: []string{
				"CREATE LOGIN appuser WITH PASSWORD='abc123'",
				"SELECT @@version",
			},
		},
		{
			name:  "GO is case-insensitive",
			input: "SELECT 1\ngo\nSELECT 2",
			want:  []string{"SELECT 1", "SELECT 2"},
		},
		{
			name:  "mixed GO and semicolons",
			input: "USE master;\nSELECT 1;\nGO\nSELECT 2;",
			want:  []string{"USE master", "SELECT 1", "SELECT 2"},
		},
		{
			name:  "windows line endings",
			input: "SELECT 1;\r\nSELECT 2;",
			want:  []string{"SELECT 1", "SELECT 2"},
		},
		{
			name:  "empty input",
			input: "",
			want:  nil,
		},
		{
			name:  "only whitespace and semicolons",
			input: "  ;  \n  ;  ",
			want:  nil,
		},
		{
			name:  "multiline statement",
			input: "SELECT id,\n  name\nFROM users\nWHERE id = 1;",
			want:  []string{"SELECT id,\n  name\nFROM users\nWHERE id = 1"},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := Split(tc.input)
			require.Equal(t, tc.want, got)
		})
	}
}
