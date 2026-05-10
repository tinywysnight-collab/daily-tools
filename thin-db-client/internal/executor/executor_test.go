package executor

import (
	"context"
	"fmt"
	"strings"
	"testing"

	sqlmock "github.com/DATA-DOG/go-sqlmock"
	"github.com/stretchr/testify/require"
)

func TestIsQuery(t *testing.T) {
	tests := []struct {
		stmt string
		want bool
	}{
		{"SELECT 1", true},
		{"select version()", true},
		{"  SELECT * FROM users", true},
		{"WITH cte AS (SELECT 1) SELECT * FROM cte", true},
		{"SHOW DATABASES", true},
		{"EXPLAIN SELECT 1", true},
		{"DESCRIBE users", true},
		{"DESC users", true},
		{"CREATE USER app IDENTIFIED BY 'secret'", false},
		{"GRANT SELECT ON mydb.* TO app", false},
		{"INSERT INTO t VALUES (1)", false},
		{"UPDATE t SET x=1", false},
		{"DELETE FROM t", false},
		{"ALTER USER hr IDENTIFIED BY newpass", false},
	}

	for _, tc := range tests {
		t.Run(tc.stmt, func(t *testing.T) {
			require.Equal(t, tc.want, isQuery(tc.stmt))
		})
	}
}

func TestExecute_SingleQuery(t *testing.T) {
	db, mock, err := sqlmock.New()
	require.NoError(t, err)
	defer db.Close()

	mock.ExpectQuery("SELECT 1").
		WillReturnRows(sqlmock.NewRows([]string{"1"}).AddRow(1))

	var buf strings.Builder
	err = Execute(context.Background(), db, []string{"SELECT 1"}, &buf, false)
	require.NoError(t, err)
	require.Contains(t, buf.String(), "1")
	require.NoError(t, mock.ExpectationsWereMet())
}

func TestExecute_SingleExec(t *testing.T) {
	db, mock, err := sqlmock.New()
	require.NoError(t, err)
	defer db.Close()

	mock.ExpectExec("CREATE USER").WillReturnResult(sqlmock.NewResult(0, 0))

	var buf strings.Builder
	err = Execute(context.Background(), db, []string{"CREATE USER app IDENTIFIED BY 'pw'"}, &buf, false)
	require.NoError(t, err)
	require.Contains(t, buf.String(), "affected")
	require.NoError(t, mock.ExpectationsWereMet())
}

func TestExecute_FailFast(t *testing.T) {
	db, mock, err := sqlmock.New()
	require.NoError(t, err)
	defer db.Close()

	mock.ExpectExec("GRANT").WillReturnError(fmt.Errorf("permission denied"))

	var buf strings.Builder
	stmts := []string{"GRANT SELECT ON t TO app", "SELECT 1"}
	err = Execute(context.Background(), db, stmts, &buf, false)
	require.Error(t, err)
	require.Contains(t, err.Error(), "permission denied")
	// Second statement must NOT have been executed.
	require.NoError(t, mock.ExpectationsWereMet())
}

func TestExecute_Verbose(t *testing.T) {
	db, mock, err := sqlmock.New()
	require.NoError(t, err)
	defer db.Close()

	mock.ExpectQuery("SELECT 1").
		WillReturnRows(sqlmock.NewRows([]string{"1"}).AddRow(1))

	var buf strings.Builder
	err = Execute(context.Background(), db, []string{"SELECT 1"}, &buf, true)
	require.NoError(t, err)
	require.Contains(t, buf.String(), "-- SELECT 1")
	require.NoError(t, mock.ExpectationsWereMet())
}

func TestExecute_MultipleStatements(t *testing.T) {
	db, mock, err := sqlmock.New()
	require.NoError(t, err)
	defer db.Close()

	mock.ExpectExec("CREATE USER").WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectExec("GRANT").WillReturnResult(sqlmock.NewResult(0, 0))

	stmts := []string{"CREATE USER app IDENTIFIED BY 'pw'", "GRANT SELECT ON t TO app"}
	var buf strings.Builder
	err = Execute(context.Background(), db, stmts, &buf, false)
	require.NoError(t, err)
	require.NoError(t, mock.ExpectationsWereMet())
}
