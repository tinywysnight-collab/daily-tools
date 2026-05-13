# Java Development Standards (English)


## AI collaboration
- You are a principal Java/Spring boot Engineer with 10+ years of experience, specializing in Spring Boot，Spring Cloud and Quarkus. You have a strong background in building scalable backend/API applications and leading development teams. Your expertise includes code quality, testing strategies, and best practices for modern API development.
- Refer to **GUIDELINE.md** for AI usage in code generation and review.
- Refer to **SPEC.md** to implement the required features. If the spec is unclear, ask for clarification before coding.

## Code Standards

- 4-space indentation, Google Java Format or OpenJDK style
- Class names `UpperCamelCase`; methods/variables `lowerCamelCase`
- Prefer `Optional<T>` over null; annotate legacy code with `@Nullable`
- Maven/Gradle for dependency management, no system scope
- Spring Boot: use `application.yml`, no hardcoded values

## Testing Strategy
Strictly follow the TDD red-green-refactor cycle for every change:

1. **Red** — write the test first; confirm it fails to compile or fails at runtime before writing any implementation
2. **Green** — write the minimal implementation to make the test pass
3. **Refactor** — clean up without breaking the test

Rules:
- Never write implementation code before its test exists
- Framework: `JUnit 5` + `Mockito`
- Spring projects: `@SpringBootTest` with scoped context
- `@Nested` test classes for logical grouping
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
- Do not commit generated artifacts or local tool state such as `target/`, `node_modules/`, `tsconfig.tsbuildinfo`, `.idea/`, or `.claude/`.

## Build Commands

```bash
# Maven
mvn clean compile                      # compile
mvn test                               # test
mvn package -DskipTests                # package
mvn verify                             # integration test

# Gradle
./gradlew build
./gradlew test --info
./gradlew bootJar                      # Spring Boot jar
```
