# Coding standards

How code in Dropoff is written and reviewed. Decided in [Coding standards for code review](https://github.com/Danie1Le/Dropoff/issues/7). Each rule has a short "why". Rules marked **(tool)** are enforced by the build, so code review skips them.

## Format

- **(tool)** palantir-java-format through Spotless: 4-space indent, 120 columns. Run `./gradlew spotlessApply` before committing. CI runs `spotlessCheck` and fails on unformatted code.
  _Why:_ formatting is never a review topic.
- **(tool)** No wildcard imports; unused imports are removed.
  _Why:_ every dependency of a file is visible at the top.

## Bugs and nulls

- **(tool)** Error Prone default checks. ERROR-level findings fail the build. WARNING-level findings print, and any new warning is a code-review finding. No Checkstyle.
  _Why:_ it catches real bug patterns at compile time with no extra tool to tune.
- **(tool)** Every package is `@NullMarked` (JSpecify), through its `package-info.java`. NullAway runs at error level in standard mode (`OnlyNullMarked=true`).
  - Mark `@Nullable` only where null is allowed.
  - Use `Optional` only as a return type.
  - Return empty collections, never null.

  _Why:_ null bugs become compile errors instead of production errors.

## Types and modules

- Use records for DTOs, events and value objects. JPA entities are plain hand-written classes. No Lombok.
  _Why:_ records are immutable and need no annotation processor; entities stay explicit.
- **(tool: Spring Modulith verification test)** A module's base package (`merchant`, `order`, `dispatch`) is its public API, and its sub-packages are internal. Other modules use only base-package types or events. Add `@NamedInterface` only when a real need appears.
  _Why:_ module boundaries stay clean, so the service split at M5 is mechanical.

## Java

- Constructor injection only. No field injection, including in tests.
  _Why:_ dependencies are explicit, and objects can be built in plain unit tests.
- Use `var` only when the type is obvious from the right-hand side.
- Don't put `final` on local variables or parameters.
  _Why:_ less noise; immutability comes from records and fields.

## Errors and logging

- Log with SLF4J and `{}` placeholders, never `System.out`.
- Domain rule violations throw unchecked domain exceptions (`DomainException` with a `code` and a category). One `@RestControllerAdvice` maps them to RFC 9457 Problem Details.
- The error codes, status rules (404 path / 409 state / 422 content) and response shapes are in `docs/PLAN.md` under "Shared contracts".
  _Why:_ HTTP stays out of the domain, and every error looks the same to clients.

## Tests

- **(tool)** `XxxTest` is a unit test, run by `./gradlew test`. `XxxIT` is an integration test against real infrastructure (Testcontainers), run by `./gradlew integrationTest`. `./gradlew build` runs both.
- Test method names are camelCase behaviour sentences, e.g. `rejectsCancellationOncePreparing`.
- Use AssertJ for assertions.
- Domain rules are written test-first. Plumbing gets tests after.
- **(tool)** JaCoCo produces a coverage report (`./gradlew jacocoTestReport`). There is no coverage gate.
  _Why:_ tests describe behaviour; coverage is a signal, not a target.

## Build and JDK

- **(tool)** Java 21 through the Gradle toolchain with the foojay resolver, so a machine without JDK 21 downloads one. CI uses Temurin 21.
- Every subproject applies `dropoff.java-conventions` from `build-logic/`. Versions live only in `gradle/libs.versions.toml`.
  _Why:_ one place to change a rule or a version.

## Git and review

- Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:` …). No AI attribution lines.
- Small PRs, one plan step each (see `docs/PLAN.md`).
- Before a PR is handed over for review, run the `code-review` skill. Post its findings on the PR, fix them, and reply to each one.
- CI must be green before merging.
