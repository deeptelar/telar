# Release guard

Three checks of a release run at the same time: an agent that reads the diff with the tools of an
MCP server, a dependency check, and a writer of release notes. `mergeRules` merges what they write.
A risky release stops before `approve`, is saved to disk, and the process ends. A second process
approves or rejects it. No API key is needed: a scripted model still calls the MCP tool.

```bash
./gradlew :samples:runReleaseGuard                       # check 1.5.0, which adds a secret: it pauses
./gradlew :samples:runReleaseGuard --args="--approve"    # a new process publishes it
./gradlew :samples:runReleaseGuard --args="--reject"     # or rejects it
RELEASE_VERSION=1.4.0 ./gradlew :samples:runReleaseGuard # a clean release is published at once
ANTHROPIC_API_KEY=your-key ./gradlew :samples:runReleaseGuard   # Claude reads the diff
```

```text
Checking release 1.5.0: three branches at once
  risk      80
  finding   [Security MCP] SECRET: the diff of 1.5.0 adds API_KEY in samples/local.properties.
  finding   [Deps] Checked 14 KMP artifacts: 1 alpha dependency detected (ktor-3.x)
  notes     ## 1.5.0
Paused before approve: a person has to decide. The run is saved, and this process ends.
```

The code is in [`Main.kt`](Main.kt), and [Human-in-the-loop](https://deeptelar.github.io/telar/guides/human-in-the-loop)
and [Parallel branches](https://deeptelar.github.io/telar/guides/parallel-branches) explain the parts.
