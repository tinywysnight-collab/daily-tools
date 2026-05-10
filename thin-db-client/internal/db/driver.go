package db

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/tinywang/daily-tools/thin-db-client/internal/cli"

	_ "github.com/lib/pq"
	_ "github.com/microsoft/go-mssqldb"
	_ "github.com/sijms/go-ora/v2"
)

// Open opens and verifies a database connection based on cfg.
func Open(ctx context.Context, cfg cli.Config) (*sql.DB, error) {
	dsn, driverName, err := buildDSN(cfg)
	if err != nil {
		return nil, err
	}

	db, err := sql.Open(driverName, dsn)
	if err != nil {
		return nil, fmt.Errorf("open %s connection: %w", cfg.DBType, err)
	}

	if err := db.PingContext(ctx); err != nil {
		db.Close()
		return nil, fmt.Errorf("ping %s at %s:%d: %w", cfg.DBType, cfg.Host, cfg.Port, err)
	}

	return db, nil
}

func buildDSN(cfg cli.Config) (dsn, driverName string, err error) {
	switch cfg.DBType {
	case cli.DBTypePostgres:
		return buildPostgresDSN(cfg), "postgres", nil
	case cli.DBTypeOracle:
		return buildOracleDSN(cfg), "oracle", nil
	case cli.DBTypeMSSQL:
		return buildMSSQLDSN(cfg), "sqlserver", nil
	default:
		return "", "", fmt.Errorf("unsupported db type: %s", cfg.DBType)
	}
}
