# Coding standards

How code in Dropoff is written and reviewed. Decided in [Coding standards for code review](https://github.com/Danie1Le/Dropoff/issues/7) unless a rule cites another source. Each rule has a one-line "why". Rules marked **(tool)** are enforced by the build, so code review skips them. Everything else, including rules marked **(review)**, is checked in code review.

## Format

- **(tool)** palantir-java-format through Spotless: 4-space indent, 120 columns. Run `./gradlew spotlessApply` before committing. CI runs `spotlessCheck` and fails on unformatted code.
  _Why:_ formatting is never a review topic.
- **(tool)** No wildcard imports; unused imports are removed.
  _Why:_ every dependency of a file is visible at the top.

## Bugs and nulls

- **(tool)** Error Prone default checks. ERROR-level findings fail the build. No Checkstyle.
  _Why:_ it catches real bug patterns at compile time with no extra tool to tune.
- **(review)** WARNING-level Error Prone findings print but don't fail the build. Any new warning is a code-review finding.
  _Why:_ warnings that nobody reads pile up and hide the next real bug.
- **(tool)** NullAway checks every `@NullMarked` (JSpecify) package at error level, in standard mode (`OnlyNullMarked=true`), and honours Spring's `@Contract` annotations (`CustomContractAnnotations=org.springframework.lang.Contract`); test code is not checked (NullAway is disabled for `compileTestJava`). JSpecify mode is not used: it is still experimental and doesn't support Oracle JDK 21.
  _Why:_ null bugs become compile errors instead of production errors.
- **(tool: NullMarkedPackagesTest)** Every package has a `package-info.java` marked `@NullMarked`.
  _Why:_ NullAway skips unmarked packages, so a missing marker silently turns the check off.
- **(review)** Mark `@Nullable` only where null is allowed. Use `Optional` only as a return type. Return empty collections, never null.
  _Why:_ callers can trust a signature without reading the body.
- **(tool: BlankCheckRuleTest)** Text fields use `@NotBlankIfPresent` (with `@NotNull` when required), never Jakarta `@NotBlank`.
  _Why:_ the check must match the `trim()` applied before storing.

## Types and modules

- Use records for DTOs, events and value objects. JPA entities are plain hand-written classes. No Lombok.
  _Why:_ records are immutable and need no annotation processor; entities stay explicit.
- **(tool: Spring Modulith verification test)** A module's base package (`merchant`, `order`, `dispatch`) is its public API, and its sub-packages are internal. Other modules use only base-package types or events. Add `@NamedInterface` only when a real need appears.
  _Why:_ module boundaries stay clean, so the service split at M5 is mechanical.

## Java

- Constructor injection only. No field injection, including in tests.
  _Why:_ dependencies are explicit, and objects can be built in plain unit tests.
- Use `var` only when the type is obvious from the right-hand side.
  _Why:_ the reader never has to look elsewhere to learn a variable's type.
- Don't put `final` on local variables or parameters.
  _Why:_ less noise; immutability comes from records and fields.

## Errors and logging

- Log with SLF4J and `{}` placeholders, never `System.out`.
  _Why:_ logs get levels and structure, and messages are only built when the level is on.
- Domain rule violations throw unchecked domain exceptions (`DomainException` with a `code` and a category). One `@RestControllerAdvice` maps them to RFC 9457 Problem Details.
  _Why:_ HTTP stays out of the domain.
- The error codes, status rules (404 path / 409 state / 422 content) and response shapes are in `docs/PLAN.md` under "Shared contracts", decided in [API conventions](https://github.com/Danie1Le/Dropoff/issues/8).
  _Why:_ every error looks the same to clients.
- **(tool: PageParamsValidationTest)** List endpoints take `@Valid PageParams`. Out-of-range paging is rejected with 400, never capped.
  _Why:_ the 100-item limit can't be skipped by forgetting `@Valid`.

## Tests

- **(tool)** `XxxTest` is a unit test, run by `./gradlew test`. `XxxIT` is an integration test against real infrastructure (Testcontainers), run by `./gradlew integrationTest`. `./gradlew build` runs both.
  _Why:_ the fast suite runs alone, and the slow one still runs before merge.
- Test method names are camelCase behaviour sentences, e.g. `rejectsCancellationOncePreparing`.
  _Why:_ a failing test's name says which behaviour broke.
- Use AssertJ for assertions.
  _Why:_ one fluent style, with failure messages that show the actual value.
- Domain rules are written test-first. Plumbing gets tests after. (Source: [Foundational decisions](https://github.com/Danie1Le/Dropoff/issues/3), round 5.)
  _Why:_ the rules are the product, so the tests pin them down before the code exists.
- **(tool)** JaCoCo produces a coverage report (`./gradlew jacocoTestReport`). There is no coverage gate.
  _Why:_ coverage is a signal, not a target.

## Build and JDK

- **(tool)** Java 21 through the Gradle toolchain with the foojay resolver, so a machine without JDK 21 downloads one. CI uses Temurin 21.
  _Why:_ every machine and CI build with the same JDK.
- Every subproject applies `dropoff.java-conventions` from `build-logic/`. Versions live only in `gradle/libs.versions.toml`, except the foojay toolchain resolver plugin, which is pinned in `settings.gradle.kts` because settings plugins are resolved before the version catalog exists.
  _Why:_ one place to change a rule or a version.

## Git and review

The PR workflow details below (one plan step per PR, posting and answering review findings, green CI) come from `docs/PLAN.md`, "How every PR works".

- Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:` …).
  _Why:_ history reads as a changelog, and authorship stays with the human.
- Small PRs, one plan step each (see `docs/PLAN.md`).
  _Why:_ small diffs get real review and are easy to revert.
- Before a PR is handed over for review, run the `code-review` skill, post its findings as PR comments, fix or answer each one, resolve them all, then mark the PR ready.
  _Why:_ mechanical findings are fixed before a human spends time on the PR.
- CI must be green before merging.
  _Why:_ `main` always builds.
