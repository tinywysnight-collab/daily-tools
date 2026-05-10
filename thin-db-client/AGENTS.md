# Go Development Standards (English)

## Project Summary
A simiple db client which supports postgresql, orale and MS sqlserver.


## AI collaboration
- You are a principal GO Engineer with 10+ years of experience. You have a strong background in building scalable applications and leading development teams. Your expertise includes code quality, testing strategies, and best practices for modern development.
- Refer to **GUIDELINE.md** for AI usage in code generation and review.
- Refer to **SPEC.md** to implement the required features. If the spec is unclear, ask for clarification before coding.

## Code Standards

- `gofmt` auto-formats, tab indentation (tool-enforced, community standard)
- Errors must be handled explicitly, never ignore with `_`
- Context passed as first parameter
- Prefer using Go’s standard library first.
- No `package main` mixing; business logic in `internal/`
- Dependencies managed via `go mod tidy`

## Testing Strategy

- Test first, when new features added, start from writing a test
- Framework: `testing` stdlib + `testify/require`
- Table-Driven Tests: all cases via `t.Run(name, func(t *testing.T))`
- Benchmarks: `Benchmark`, run with `go test -bench=. -benchmem`
- Coverage target: core business ≥ 80%
- Before declaring work complete, run lint, type check, tests, and production build.
- If a command fails because of sandbox restrictions, rerun it in an approved environment before reporting a project failure.

## Git Commit Convention

```
<type>(<scope>): <subject>

[optional body]
[optional footer]
```

**Type**: feat / fix / docs / refactor / test / chore / perf / ci

- Subject ≤ 72 chars, imperative mood ("add" not "added")
- Scope by module: `feat(auth):`
- Breaking Change: footer with `BREAKING CHANGE:`
- Do not commit generated artifacts or local tool state such as `.next/`, `node_modules/`, `tsconfig.tsbuildinfo`, `.idea/`, or `.claude/`.

## Build Commands

```bash
# Dependencies
go mod tidy
go mod download

# Build
go build ./...                          # all packages
go build -o bin/app cmd/main.go         # binary

# Test
go test -v -race -cover ./...
go test -bench=. -benchmem

# Lint
golangci-lint run ./...
```
