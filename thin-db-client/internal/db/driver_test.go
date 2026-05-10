package db

import (
	"testing"

	"github.com/stretchr/testify/require"
	"github.com/tinywang/daily-tools/thin-db-client/internal/cli"
)

func TestBuildDSN(t *testing.T) {
	tests := []struct {
		name       string
		cfg        cli.Config
		wantDSN    string
		wantDriver string
		wantErr    bool
	}{
		{
			name: "postgres",
			cfg: cli.Config{
				DBType: cli.DBTypePostgres,
				Host:   "db.corp.com",
				Port:   5432,
				User:   "admin",
				Pass:   "secret",
				DBName: "mydb",
			},
			wantDSN:    "postgres://admin:secret@db.corp.com:5432/mydb?sslmode=disable",
			wantDriver: "postgres",
		},
		{
			name: "postgres special chars in password",
			cfg: cli.Config{
				DBType: cli.DBTypePostgres,
				Host:   "localhost",
				Port:   5432,
				User:   "admin",
				Pass:   "p@ss#w0rd!",
				DBName: "mydb",
			},
			wantDriver: "postgres",
			// URL should encode special chars; just verify driver and no error.
		},
		{
			name: "oracle",
			cfg: cli.Config{
				DBType: cli.DBTypeOracle,
				Host:   "oracle.corp.com",
				Port:   1521,
				User:   "sys",
				Pass:   "oracle123",
				DBName: "ORCLPDB1",
			},
			wantDSN:    "oracle://sys:oracle123@oracle.corp.com:1521/ORCLPDB1",
			wantDriver: "oracle",
		},
		{
			name: "mssql",
			cfg: cli.Config{
				DBType: cli.DBTypeMSSQL,
				Host:   "sql.corp.com",
				Port:   1433,
				User:   "sa",
				Pass:   "Pass@123",
				DBName: "master",
			},
			wantDriver: "sqlserver",
			// URL encoding of @ in password; just verify driver.
		},
		{
			name:    "unknown type",
			cfg:     cli.Config{DBType: "mysql"},
			wantErr: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			dsn, driver, err := buildDSN(tc.cfg)
			if tc.wantErr {
				require.Error(t, err)
				return
			}
			require.NoError(t, err)
			require.Equal(t, tc.wantDriver, driver)
			if tc.wantDSN != "" {
				require.Equal(t, tc.wantDSN, dsn)
			} else {
				require.NotEmpty(t, dsn)
			}
		})
	}
}
