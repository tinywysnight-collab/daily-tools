package cli

import (
	"errors"
	"flag"
	"fmt"
	"os"
	"time"
)

// DBType constants for supported databases.
const (
	DBTypePostgres = "postgres"
	DBTypeOracle   = "oracle"
	DBTypeMSSQL    = "mssql"
)

// Config holds all parsed CLI options.
type Config struct {
	DBType  string
	Host    string
	Port    int
	User    string
	Pass    string
	DBName  string
	File    string
	Stmt    string
	Timeout time.Duration
	Verbose bool
}

// Parse parses args (typically os.Args[1:]) into a Config.
// THINDB_PASS env var takes precedence over the -pass flag.
func Parse(args []string) (Config, error) {
	fs := flag.NewFlagSet("thindb", flag.ContinueOnError)

	var cfg Config
	var passFlag string
	var timeoutSec int

	fs.StringVar(&cfg.DBType, "type", "", "Database type: postgres, oracle, mssql (required)")
	fs.StringVar(&cfg.Host, "host", "localhost", "Server hostname")
	fs.IntVar(&cfg.Port, "port", 0, "Server port (default depends on -type)")
	fs.StringVar(&cfg.User, "user", "", "Username (required)")
	fs.StringVar(&passFlag, "pass", "", "Password (prefer env var THINDB_PASS)")
	fs.StringVar(&cfg.DBName, "dbname", "", "Database/service name (required)")
	fs.StringVar(&cfg.File, "file", "", "Path to SQL script file")
	fs.StringVar(&cfg.Stmt, "stmt", "", "Single SQL statement to execute")
	fs.IntVar(&timeoutSec, "timeout", 30, "Query timeout in seconds")
	fs.BoolVar(&cfg.Verbose, "v", false, "Verbose: print each statement before executing")

	if err := fs.Parse(args); err != nil {
		return Config{}, fmt.Errorf("flag error: %w", err)
	}

	// Env var takes precedence over flag for password.
	if envPass := os.Getenv("THINDB_PASS"); envPass != "" {
		cfg.Pass = envPass
	} else {
		cfg.Pass = passFlag
	}

	cfg.Timeout = time.Duration(timeoutSec) * time.Second

	// Positional arg fallback for SQL statement.
	if cfg.File == "" && cfg.Stmt == "" && fs.NArg() == 1 {
		cfg.Stmt = fs.Arg(0)
	}

	// Apply default ports.
	if cfg.Port == 0 {
		cfg.Port = defaultPort(cfg.DBType)
	}

	return cfg, validate(cfg)
}

func defaultPort(dbType string) int {
	switch dbType {
	case DBTypePostgres:
		return 5432
	case DBTypeOracle:
		return 1521
	case DBTypeMSSQL:
		return 1433
	default:
		return 0
	}
}

func validate(cfg Config) error {
	var errs []error

	if cfg.DBType == "" {
		errs = append(errs, errors.New("-type is required (postgres, oracle, mssql)"))
	} else if cfg.DBType != DBTypePostgres && cfg.DBType != DBTypeOracle && cfg.DBType != DBTypeMSSQL {
		errs = append(errs, fmt.Errorf("unknown -type %q: must be postgres, oracle, or mssql", cfg.DBType))
	}

	if cfg.User == "" {
		errs = append(errs, errors.New("-user is required"))
	}

	if cfg.DBName == "" {
		errs = append(errs, errors.New("-dbname is required"))
	}

	if cfg.File != "" && cfg.Stmt != "" {
		errs = append(errs, errors.New("-file and -stmt are mutually exclusive"))
	}

	if cfg.File == "" && cfg.Stmt == "" {
		errs = append(errs, errors.New("one of -file, -stmt, or a positional SQL argument is required"))
	}

	return errors.Join(errs...)
}
