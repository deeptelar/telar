# Take over: `samples/release-guard`

A handoff note for the agent (or person) that continues this branch on a machine that can build
the project. **Delete this file in the last commit before the branch is merged.** It is not
documentation of the project.

- Branch: `ccr-e4db339c-jvx1ek`
- Commits: `1f97e03` (first version of the sample), `3194cf6` (the version to verify), `c625b67`
  (this note). Verified at `c625b67` on 2026-10-16; the code has not changed since.
- Read first: [CLAUDE.md](CLAUDE.md) and [CONTRIBUTING.md](CONTRIBUTING.md). Their rules apply.

## Status

**The sample compiles, its 10 tests pass, and it works by hand.** The first session could not
download Kotlin 2.4, so nothing had been compiled. A second session (2026-10-16) built it on the
JVM with no change to the code: none of the likely breakages listed below happened.

| Step | Result on 2026-10-16 |
|---|---|
| 1. `:samples:release-guard:compileTestKotlin` | Passes, first try, no warnings (`allWarningsAsErrors` is on) |
| 2. `:samples:release-guard:test` | 10 of 10 pass, including `the decision is made in another process than the audit` |
| 3. The sample by hand | Works: a pause, a fork (revision 2, changelog grouped by area), an approval, a reset, each in its own process (ids 1236, 1345, 1439, 1537) |
| 4. `:samples:release-guard:ktlintCheck` | Passes |
| 5. `check apiCheck` | **Partly run.** Everything on the JVM passes: the tests of all 13 modules, `:samples:test`, every `koverVerify` and ktlint, and `apiCheck` of `telar-langchain4j`, `telar-otel` and `telar-checkpoint-browser`. The JS, Wasm and native tasks and the `apiCheck` of the multiplatform modules did not run, because that machine could not download their tools (see below) |
| 5. Docs site (`npm ci && npm run build`) | Builds, so no dead link |

What is left is step 5 on a machine with full network access, or CI, which does it on every push.
This branch only adds a module that `apiCheck` ignores, so a failure there would most likely not
come from it. But `main` itself is red at the moment: the macOS job fails in
`:telar-checkpoint-sqldelight:wasmJsBrowserTest`, because Karma does not find
`sql.js/dist/sql-wasm.wasm` (CI run 241, after #135). It passed on the commit before, so it is
probably intermittent. CI on this branch may hit it too; that would not come from this branch.

### How it was built without full network access

Notes for an agent in a restricted cloud container; on a normal machine skip this.

- Maven Central answered `429 Too Many Requests`. An init script outside the repository sent
  dependencies to Google's mirror, `https://maven-central.storage-download.googleapis.com/maven2/`,
  with `gradlePluginPortal()` kept for the ktlint plugin. Pass it with `--init-script`.
- The container had only JDK 21; the toolchain wants 17. `apt-get install openjdk-17-jdk-headless`,
  then `-Porg.gradle.java.installations.paths=/usr/lib/jvm/java-17-openjdk-amd64`.
- The proxy refused `download.jetbrains.com` (Kotlin/Native) and `codeload.github.com` (the Karma
  fork of the JS tests) with 403. That is why step 5 is partial. Allowing those hosts in the
  environment's network settings would let it run.

### Two versions of the sample exist

Branch `ccr-9186b6c5-i75a94` has a smaller sample with the same package name: `ReleaseGuard`, inside
the `samples` module (`samples/src/main/kotlin/.../releaseguard/`), run with
`./gradlew :samples:runReleaseGuard`. It runs three branches through `mergeRules`, pauses before an
`approve` node only for a risky release, and resumes with `--approve` or `--reject` in a new process.
It has no git, no fork and no evidence-based risk, and it has 4 tests, which pass. Both branches
change `docs/samples.md`.

**Merge one, not both.** This branch is the more complete one and matches the plan of this note.
If it is chosen, close the other branch, or take only what this one lacks from it. Do not merge both:
they would put two samples with the same package and two sections in `docs/samples.md`.

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

None of these broke on 2026-10-16 (JDK 17, git 2.43). They stay here for a machine where something
does break, from most to least likely:

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
- **A detached HEAD prints "HEAD is on HEAD, not on main".** It is only a warning, and on a clone
  of `main` it does not appear. If you touch `ReleaseGuard.kt`, consider "HEAD is detached" for that
  case.
- **Thread ids replace dots with dashes** (`release-1-1-0`), because `FileCheckpointer` would write
  `release-1%2E1%2E0.json`.
- **The secret patterns are specific** (AWS key, private key, GitHub token, Anthropic key). A
  generic "password =" pattern was removed because it flagged the repository's own code. The test
  builds its fake AWS key from two strings so that no file in the repository contains one.

## Done when

- [x] `./gradlew :samples:release-guard:test` passes, all 10 tests, including the two-process one
- [x] Step 3 works by hand: a pause, then a fork, then an approval in separate processes, with the
      process ids shown different
- [ ] `./gradlew check apiCheck` passes, and the docs site builds. The docs site builds and the JVM
      part of `check` passes; the JS, Wasm and native tasks still have to run, locally or in CI
- [ ] Choose between this branch and `ccr-9186b6c5-i75a94` (see "Two versions of the sample exist")
- [x] Any fix is committed with the repository's style (`Samples: ...`, imperative), and
      `docs/samples.md` still matches the real output and flags. No fix was needed
- [ ] This file is deleted in the last commit, then the branch is pushed:
      `git push -u origin ccr-e4db339c-jvx1ek`
- [ ] A pull request is opened only if the user asks for one
