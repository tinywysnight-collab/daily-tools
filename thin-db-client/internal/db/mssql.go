package db

import (
	"fmt"
	"net/url"

	"github.com/tinywang/daily-tools/thin-db-client/internal/cli"
)

func buildMSSQLDSN(cfg cli.Config) string {
	// go-mssqldb uses sqlserver:// URL format.
	u := &url.URL{
		Scheme: "sqlserver",
		User:   url.UserPassword(cfg.User, cfg.Pass),
		Host:   fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
	}
	q := url.Values{}
	q.Set("database", cfg.DBName)
	u.RawQuery = q.Encode()
	return u.String()
}
