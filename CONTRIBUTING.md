# Contributing

Thanks for considering a contribution to `cb-blob-store`.

## Quick start

```bash
git clone https://github.com/<your-fork>/cb-blob-store.git
cd cb-blob-store
mvn install
```

Requires JDK 17+ and Maven 3.9+.

## How to propose a change

1. **Open an issue first** for anything non-trivial — bug, design change,
   new feature. A short description of the problem and the proposed shape
   of the fix saves rework.
2. **Fork and branch** from `main`. Use a descriptive branch name
   (`fix/streaming-empty-input`, `feat/dedup-design`, etc.).
3. **Make the change in small, reviewable commits.** Each commit should
   compile and pass tests.
4. **Add tests.** A bug fix needs a regression test; a feature needs unit
   tests covering the happy path and at least one failure mode.
5. **Update the docs.** If you change behavior, update `README.md`,
   `docs/ARCHITECTURE.md`, and/or `docs/RUNBOOK.md` to match.
6. **Open a pull request.** Reference the issue. Describe what changed,
   why, and what you tested.

## Code style

- **Java 17** language level. Prefer records and pattern matching where
  they make code clearer; don't force them.
- **Two-space indentation in XML, four-space in Java** (matches what's
  already in the tree).
- **No wildcard imports.**
- **SLF4J for logging**, never `System.out` from library code. Use MDC
  keys (`blobId`, `principal`, `ip`) for correlation.
- **Public API is the contract.** Anything in `io.cbblobstore.core` is
  public; `io.cbblobstore.core.internal` is not.

## Tests

```bash
mvn -pl cb-blob-store-core test          # core only
mvn -pl cb-blob-store-rest test          # rest only
mvn test                                  # everything
```

Tests are hermetic — no Couchbase or S3 needed locally. Mocks cover both
backends. If you write a test that needs a real backend, mark it with a
JUnit `@Tag("integration")` so it stays out of the default run.

## Reporting security issues

Please **don't** open public issues for security problems. Email the
maintainer (or use GitHub's private vulnerability reporting if enabled)
with a description, reproduction steps, and any patch you have.

## What kinds of changes are welcome

Most welcome:

- Bug fixes with a reproducing test.
- Improvements to error messages and logging.
- Documentation fixes and clarifications.
- New `BlobAnalytics` query helpers (see ARCHITECTURE §8b for the shape).
- New Eventing recipes for `docs/RUNBOOK.md` §10.
- Performance work backed by a benchmark.

Out of scope for `cb-blob-store` (these belong elsewhere):

- Anything that buffers entire payloads in JVM heap — the streaming path
  is the contract.
- An in-cluster Java extension to Couchbase — Server has no Java SPI; this
  library is the client-side answer.
- Support for backends that aren't Couchbase + S3-compatible.
