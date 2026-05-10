package db

import (
	"fmt"

	"github.com/tinywang/daily-tools/thin-db-client/internal/cli"
)

func buildOracleDSN(cfg cli.Config) string {
	// go-ora DSN format: oracle://user:pass@host:port/service
	return fmt.Sprintf("oracle://%s:%s@%s:%d/%s",
		cfg.User, cfg.Pass, cfg.Host, cfg.Port, cfg.DBName)
}
