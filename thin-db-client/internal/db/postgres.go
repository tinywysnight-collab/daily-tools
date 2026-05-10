package db

import (
	"fmt"
	"net/url"

	"github.com/tinywang/daily-tools/thin-db-client/internal/cli"
)

func buildPostgresDSN(cfg cli.Config) string {
	// Use URL format so special characters in password are handled safely.
	u := &url.URL{
		Scheme: "postgres",
		User:   url.UserPassword(cfg.User, cfg.Pass),
		Host:   fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
		Path:   "/" + cfg.DBName,
	}
	q := url.Values{}
	q.Set("sslmode", "disable")
	u.RawQuery = q.Encode()
	return u.String()
}
