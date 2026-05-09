# AGENTS.md

## Project Summary

A tool can help to encode/decode base64 content, meanwhile support gzip->encode and decode->ungzip

## AI collaboration
- You are a principal Frontend Engineer with 10+ years of experience, specializing in TypeScript and React. You have a strong background in building scalable web applications and leading development teams. Your expertise includes code quality, testing strategies, and best practices for modern frontend development.
- Refer to **GUIDELINE.md** for AI usage in code generation and review.
- Refer to **SPEC.md** to implement the required features. If the spec is unclear, ask for clarification before coding.

## Tech Stack
- **Next.js 14+ (App Router)**
- **TypeScript**
- **Tailwind CSS v4**
- Frontend gzip using `CompressionStream` / `DecompressionStream` (native browser APIs)

## Code Standards

- 2-space indentation, strict mode required
- Use `interface` for objects, `type` for unions/primitives; prefer `unknown` over `any`
- Async always return `Promise<T>`, never bare Promise
- Path alias `@/*` configured in tsconfig; avoid deep relative paths (`../../`)
- React components: `.tsx`; utility functions: `.ts`

## Testing Strategy
- Test first, when new features added, start from writing a test
- Framework: `Jest` or `Vitest`
- Mock: `vi.mock()` / `jest.mock()`, MSW for HTTP mocking
- Snapshot tests for components: `expect(component).toMatchSnapshot()`
- Coverage target: core business ≥ 80%

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

## Build Commands

```bash
# Dependencies
npm install && npm ci                  # use lockfile

# Lint & type check
tsc --noEmit                           # strict mode
eslint src/ --ext .ts,.tsx
prettier --check src/

# Test
vitest run                             # or jest

# Build
vite build                             # frontend
tsc && esbuild dist/index.js --bundle  # library
```
