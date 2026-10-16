# Take over: `samples/release-guard`

A handoff note for the agent (or person) that continues this branch on a machine that can build
the project. **Delete this file in the last commit before the branch is merged.** It is not
documentation of the project.

- Branch: `ccr-e4db339c-jvx1ek`
- Commits: `1f97e03` (first version of the sample), `3194cf6` (the version to verify)
- Read first: [CLAUDE.md](CLAUDE.md) and [CONTRIBUTING.md](CONTRIBUTING.md). Their rules apply.

## Status in one sentence

The code is written, ktlint-clean and pushed, but **it has never been compiled and no test has run**.
The cloud session that wrote it could not download the Kotlin 2.4 artifacts from Maven Central
(the mirror answered 404 and 429), so `./gradlew` failed while configuring `build-logic`, before
any of this code was compiled.

Your job: compile it, run its tests and the sample, fix what fails, then run what CI runs.

## What the sample is

`./gradlew :samples:release-guard:run` audits the repository it was cloned into before a tag is
published, with no API key, Docker or Node.js. It must show these features:

| Feature | Where |
|---|---|
| A data class as the state | `ReleaseState` in `ReleaseGuard.kt` |
| `telar-mcp` with nothing to install | `ReleaseTools.kt`: an MCP server with `read_diff` and `scan_secrets`, written with the server side of the MCP SDK, connected in the process over `ChannelTransport`. `Main.kt` reads its tools with `mcpTools(client)` |
| Parallel branches and `mergeRules` | `releaseGuardGraph` in `ReleaseGuard.kt`: `security_agent` (a `toolAgent` as a `subgraph`), `dependency_audit` and `changelog_writer` all start from `START`; `append` findings, `merge` risk with the highest value, `replace` changelog |
| `interruptBefore` and `FileCheckpointer`, across processes | The run stops before `publish`, saves to `samples/release-guard/.checkpoints/release-<version>.json`, and the JVM exits. `--approve` / `--reject` resume it in a new JVM. `--fork [instruction]` forks from the checkpoint before `verdict` and rewrites the changelog on the thread `release-<version>-r2` |
| A model, or none | `Models.kt`: `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` picks a real model; without a key, `scriptedModel` answers the same way every time |

Files:

```
samples/release-guard/
  build.gradle.kts                 application plugin; `run` passes -DreleaseGuard.repository=<root> and -DreleaseGuard.home=<this dir>
  src/main/kotlin/dev/deeptelar/telar/samples/releaseguard/
    Main.kt           CLI: --audit (default), --approve, --reject, --fork, --reset; one command per process
    ReleaseGuard.kt   state, prepareRelease (git, before the graph), the graph, merge rules, the nodes
    ReleaseTools.kt   the in-process MCP server and scanForSecrets
    Models.kt         scriptedModel and modelFor
    Repository.kt     runs git on Dispatchers.IO, reads files, propertyOf, nextTag
  src/test/kotlin/.../ReleaseGuardTest.kt   10 tests, each in a temporary git repository
```

Also changed: `settings.gradle.kts` (`include("samples:release-guard")`),
`build-logic/src/main/kotlin/telar.root.gradle.kts` (`ignoredProjects.add("release-guard")` for
`apiCheck`), `.gitignore` (`.checkpoints/`), `docs/samples.md`, `CHANGELOG.md`.

## Steps

Requirements: JDK 17+, `git` on the `PATH`, network access to Maven Central and the Gradle plugin
portal. For the step 5 browser tests (`check`), Chrome or Chromium (`CHROME_BIN`).

```bash
git fetch origin ccr-e4db339c-jvx1ek
git checkout ccr-e4db339c-jvx1ek

# 1. Compile. Expect to fix compile errors here first; nothing has been compiled yet.
./gradlew :samples:release-guard:compileTestKotlin

# 2. The tests of the sample.
./gradlew :samples:release-guard:test

# 3. The sample by hand, as a new user would run it (two separate processes).
./gradlew :samples:release-guard:run
cat samples/release-guard/.checkpoints/release-*.json
./gradlew :samples:release-guard:run --args="--fork Group the changes by area."
./gradlew :samples:release-guard:run --args=--approve
./gradlew :samples:release-guard:run --args=--reset

# 4. Formatting.
./gradlew :samples:release-guard:ktlintCheck   # or ktlintFormat

# 5. What CI runs, before pushing.
./gradlew check apiCheck
(cd docs && npm ci && npm run build)          # docs/samples.md changed; this fails on a dead link
```

The run in step 3 must reach `Paused before "publish"` on a fresh, clean clone of `main` with tags.
The working tree must be clean for the "clean working tree" finding to be ok. `.checkpoints/` and
`build/` are git-ignored.

## Where it is most likely to break

Check these first, from most to least likely:

1. **Compile errors.** The spots most likely to break:
   - The `addTool { ... return@addTool ... }` handlers in `ReleaseTools.kt`. The MCP SDK 0.15
     handler is `suspend ClientConnection.(CallToolRequest) -> CallToolResult`.
   - Type inference of `mergeRules { ... }` passed as `compile(rules = ...)`. If it fails, write
     `mergeRules<ReleaseState> { ... }`.
   - The destructured `update` lambda `{ release, (found, risk) -> ... }` of `dependency_audit`.
   - `allWarningsAsErrors` is on, so an unused variable or import fails the build.
2. **`the decision is made in another process than the audit`** starts `MainKt` with
   `System.getProperty("java.class.path")` of the Gradle test worker. If that classpath is
   incomplete (`ClassNotFoundException`), pass the runtime classpath from `build.gradle.kts`
   instead, for example with a `jvmArgumentProviders` entry that sets a system property from
   `sourceSets.main.get().runtimeClasspath` (keep it lazy for the configuration cache).
3. **Exact-text assertions** on the report, which depend on column padding and on line order:
   - `"[ok]    security_agent    No secrets in the"` (`padEnd` uses the longest source name,
     `dependency_audit`, 16 characters)
   - the changelog line order, newest commit first, as `git log` prints it
   - `"Risk: 4/10"`

   Fix the test or the output, whichever is wrong; keep the output readable.
4. **The engine with `mergeRules`.** With rules, *every* step is merged by them, including steps
   with a single node. Every property that a node changes needs a rule: today that is
   `findings`, `risk`, `changelog`, `securitySummary`, `outcome`, `published`. `decision` and
   `revision` are changed only by the `resume` and `fork` updates, not by nodes. A
   `MergeRuleException` means a node changes a property without a rule.
5. **`--fork`** looks for `graph.history(config).last { "verdict" in it.nextNodes }`. That is the
   checkpoint after step 1 (the three branches). A fork saves its start checkpoint first (see
   `forkEvents` in `CompiledGraph.kt`), so forking a fork also finds one.
6. **MCP in tests.** The tests use `runBlocking`, not `runTest`, on purpose. With `runTest`, the
   virtual clock would run past the MCP client's request timeout, as `McpSampleTest` explains.
   Keep it that way.
7. **Git config on the machine.** The tests commit with `-c user.name/-c user.email -c
   commit.gpgsign=false` and `git init -b main` (git 2.28+). A global `diff.noprefix=true` would
   break the `b/` prefix that `scanForSecrets` strips. If that bites, add `--src-prefix=a/
   --dst-prefix=b/` to the `git diff` calls in `ReleaseTools.kt`.

## Design decisions to keep

They were made on purpose; change them only with a reason:

- **The guard never changes the repository.** `publish` writes release notes to
  `samples/release-guard/build/release-notes/` and prints the `git tag` and `git push` commands.
- **`publish` checks the repository again.** It refuses when HEAD moved, or the tag appeared,
  after the audit.
- **Risk scores come from evidence, not from the model.** A secret is 10, a scan that did not run
  is 5, a pre-release dependency is 4, a changed version is 3, a clean scan is 1, and git warnings
  are 2. The agent's answer is only the summary.
- **Only facts that make a tag wrong are blockers:** a secret, a snapshot dependency, an existing
  tag, or no commits. Everything else is a warning shown at the pause, so a fresh clone of `main`
  reaches the pause.
- **Thread ids replace dots with dashes** (`release-1-1-0`), because `FileCheckpointer` would write
  `release-1%2E1%2E0.json`.
- **The secret patterns are specific** (AWS key, private key, GitHub token, Anthropic key). A
  generic "password =" pattern was removed because it flagged the repository's own code. The test
  builds its fake AWS key from two strings so that no file in the repository contains one.

## Done when

- [ ] `./gradlew :samples:release-guard:test` passes, all 10 tests, including the two-process one
- [ ] Step 3 works by hand: a pause, then a fork, then an approval in separate processes, with the
      process ids shown different
- [ ] `./gradlew check apiCheck` passes, and the docs site builds
- [ ] Any fix is committed with the repository's style (`Samples: ...`, imperative), and
      `docs/samples.md` still matches the real output and flags
- [ ] This file is deleted in the last commit, then the branch is pushed:
      `git push -u origin ccr-e4db339c-jvx1ek`
- [ ] A pull request is opened only if the user asks for one
