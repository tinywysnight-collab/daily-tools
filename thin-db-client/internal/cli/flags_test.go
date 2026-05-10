package cli

import (
	"os"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestParse(t *testing.T) {
	baseArgs := []string{"-type", "postgres", "-host", "localhost", "-user", "admin", "-pass", "secret", "-dbname", "mydb"}

	tests := []struct {
		name    string
		args    []string
		env     map[string]string
		want    Config
		wantErr string
	}{
		{
			name: "stmt flag",
			args: append(baseArgs, "-stmt", "SELECT 1"),
			want: Config{
				DBType: "postgres", Host: "localhost", Port: 5432,
				User: "admin", Pass: "secret", DBName: "mydb",
				Stmt: "SELECT 1", Timeout: 30 * time.Second,
			},
		},
		{
			name: "file flag",
			args: append(baseArgs, "-file", "init.sql"),
			want: Config{
				DBType: "postgres", Host: "localhost", Port: 5432,
				User: "admin", Pass: "secret", DBName: "mydb",
				File: "init.sql", Timeout: 30 * time.Second,
			},
		},
		{
			name: "positional sql arg",
			args: append(baseArgs, "SELECT version()"),
			want: Config{
				DBType: "postgres", Host: "localhost", Port: 5432,
				User: "admin", Pass: "secret", DBName: "mydb",
				Stmt: "SELECT version()", Timeout: 30 * time.Second,
			},
		},
		{
			name: "env var overrides pass flag",
			args: append(baseArgs, "-stmt", "SELECT 1"),
			env:  map[string]string{"THINDB_PASS": "envpass"},
			want: Config{
				DBType: "postgres", Host: "localhost", Port: 5432,
				User: "admin", Pass: "envpass", DBName: "mydb",
				Stmt: "SELECT 1", Timeout: 30 * time.Second,
			},
		},
		{
			name: "oracle default port",
			args: []string{"-type", "oracle", "-user", "sys", "-pass", "pw", "-dbname", "ORCLPDB1", "-stmt", "SELECT 1 FROM dual"},
			want: Config{
				DBType: "oracle", Host: "localhost", Port: 1521,
				User: "sys", Pass: "pw", DBName: "ORCLPDB1",
				Stmt: "SELECT 1 FROM dual", Timeout: 30 * time.Second,
			},
		},
		{
			name: "mssql default port",
			args: []string{"-type", "mssql", "-user", "sa", "-pass", "pw", "-dbname", "master", "-stmt", "SELECT @@version"},
			want: Config{
				DBType: "mssql", Host: "localhost", Port: 1433,
				User: "sa", Pass: "pw", DBName: "master",
				Stmt: "SELECT @@version", Timeout: 30 * time.Second,
			},
		},
		{
			name: "custom port and timeout",
			args: []string{"-type", "postgres", "-host", "db.corp.com", "-port", "15432", "-user", "admin", "-pass", "pw", "-dbname", "mydb", "-timeout", "60", "-v", "-stmt", "SELECT 1"},
			want: Config{
				DBType: "postgres", Host: "db.corp.com", Port: 15432,
				User: "admin", Pass: "pw", DBName: "mydb",
				Stmt: "SELECT 1", Timeout: 60 * time.Second, Verbose: true,
			},
		},
		{
			name:    "missing -type",
			args:    []string{"-user", "admin", "-pass", "pw", "-dbname", "mydb", "-stmt", "SELECT 1"},
			wantErr: "-type is required",
		},
		{
			name:    "unknown db type",
			args:    []string{"-type", "mysql", "-user", "admin", "-pass", "pw", "-dbname", "mydb", "-stmt", "SELECT 1"},
			wantErr: `unknown -type "mysql"`,
		},
		{
			name:    "missing -user",
			args:    []string{"-type", "postgres", "-pass", "pw", "-dbname", "mydb", "-stmt", "SELECT 1"},
			wantErr: "-user is required",
		},
		{
			name:    "missing -dbname",
			args:    []string{"-type", "postgres", "-user", "admin", "-pass", "pw", "-stmt", "SELECT 1"},
			wantErr: "-dbname is required",
		},
		{
			name:    "file and stmt mutually exclusive",
			args:    append(baseArgs, "-file", "init.sql", "-stmt", "SELECT 1"),
			wantErr: "mutually exclusive",
		},
		{
			name:    "no sql provided",
			args:    baseArgs,
			wantErr: "one of -file, -stmt",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			for k, v := range tc.env {
				t.Setenv(k, v)
			}
			if _, ok := tc.env["THINDB_PASS"]; !ok {
				os.Unsetenv("THINDB_PASS")
			}

			got, err := Parse(tc.args)
			if tc.wantErr != "" {
				require.Error(t, err)
				require.Contains(t, err.Error(), tc.wantErr)
				return
			}
			require.NoError(t, err)
			require.Equal(t, tc.want, got)
		})
	}
}
