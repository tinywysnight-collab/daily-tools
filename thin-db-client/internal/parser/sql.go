package parser

import (
	"strings"
)

// Split splits a SQL script into individual statements.
//
// Strategy:
//  1. Split on lines containing only "GO" (case-insensitive) — MS SQL Server batch separator.
//  2. Split each batch on ";".
//  3. Trim whitespace and filter blank results.
//
// Known limitation: PL/SQL BEGIN...END blocks with interior semicolons will be
// incorrectly split. This is acceptable for the stated use cases (DDL, grants, queries).
func Split(script string) []string {
	// Normalize line endings.
	script = strings.ReplaceAll(script, "\r\n", "\n")

	// Step 1: split on standalone GO lines.
	batches := splitOnGO(script)

	var stmts []string
	for _, batch := range batches {
		// Step 2: split each batch on semicolons.
		for _, part := range strings.Split(batch, ";") {
			s := strings.TrimSpace(part)
			if s != "" {
				stmts = append(stmts, s)
			}
		}
	}
	return stmts
}

// splitOnGO splits script on lines that contain only "GO" (case-insensitive),
// optionally surrounded by whitespace.
func splitOnGO(script string) []string {
	var batches []string
	var current strings.Builder

	for _, line := range strings.Split(script, "\n") {
		if strings.EqualFold(strings.TrimSpace(line), "go") {
			if b := strings.TrimSpace(current.String()); b != "" {
				batches = append(batches, b)
			}
			current.Reset()
		} else {
			current.WriteString(line)
			current.WriteByte('\n')
		}
	}

	if b := strings.TrimSpace(current.String()); b != "" {
		batches = append(batches, b)
	}
	return batches
}
