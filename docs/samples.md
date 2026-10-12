# Samples

Runnable examples live in the repository, in [`samples/`](https://github.com/deeptelar/telar/tree/main/samples/src/main/kotlin/dev/deeptelar/telar/samples):

```bash
./gradlew :samples:runQuickStart        # the email support agent of the quick start
./gradlew :samples:runDecisionRouter    # the same agent, routed by a decision model
./gradlew :samples:runHumanInTheLoop    # a refund that waits for approval, saved to disk
./gradlew :samples:runReviewLoop        # a reviewer approves a draft or sends it back
./gradlew :samples:runAskFromANode      # a payout that asks for approval only when it is large
./gradlew :samples:runSubgraph          # the payout graph as one node of a larger graph
./gradlew :samples:runParallelResearch  # three lookups at the same time
./gradlew :samples:runChatAgent         # a chat agent built on a LangChain4j model
./gradlew :samples:runToolAgent         # an agent that calls tools, with or without an API key
./gradlew :samples:runMcpTools          # the same agent, with its tools on an MCP server
./gradlew :samples:runKitchenRush       # a model runs a pizzeria for a shift, on a token budget
./gradlew :samples:runLevel1            # ... runLevel8, the levels of the tutorial
```

## The kitchen: one shift, measured

[`kitchen/`](https://github.com/deeptelar/telar/tree/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/kitchen)
is a larger sample. Models run a pizzeria for one shift of twelve orders. They make, bake and serve
pizzas with tools, while orders arrive, change and are cancelled, and the oven has two places. The
shift is the same every time, so one result can be compared with another: first the points for the
orders, then the tokens. The shift ends when the kitchen closes, when every order is served, or when
the token budget is spent.

The same shift runs in three kitchens, and `KITCHEN` names the one to run:

| `KITCHEN` | The kitchen | What it shows |
|---|---|---|
| `solo` (the default) | One chef, a model, has every tool and does everything. | The loop of an agent. The chef gets the whole conversation with every answer, so each answer costs more than the one before. |
| `line` | Three stations work at the same time. A model reads the orders and writes a ticket for each, and rules in plain code make and bake the pizzas. | A model only where one is needed. The stations are nodes that run in parallel. |
| `line-models` | The same three stations, with a model at each. | A station's model is asked with what it needs to know now, not with a conversation. |

```bash
./gradlew :samples:runKitchenRush                                         # the solo chef, scripted: no key needed
KITCHEN=line ./gradlew :samples:runKitchenRush                            # the line, scripted
ANTHROPIC_API_KEY=your-key ./gradlew :samples:runKitchenRush              # the solo chef is Claude
ANTHROPIC_API_KEY=your-key ANTHROPIC_MODEL=claude-haiku-5-5 KITCHEN=line ./gradlew :samples:runKitchenRush
OPENAI_API_KEY=your-key OPENAI_MODEL=gpt-5-mini TOKEN_BUDGET=100000 ./gradlew :samples:runKitchenRush
```

The model is chosen like the one of `runToolAgent`, see
[Agents with tools](guides/agents-with-tools.md). A shift with a real model asks it many times, so
it costs more than the other samples: `TOKEN_BUDGET` sets the most tokens it may use, and the
default is 300000. The run ends with its result:

```text
Lunch rush, the line, a model reads the orders and rules do the rest (scripted: no model was asked, and the tokens are a guess)
orders   11 of 11 on time
points   1100 of 1100
tokens   6105 (5867 read, 238 written) in 10 answers
stations reader 6105 tokens in 10 answers, prep 0 tokens in 0 answers, oven 0 tokens in 0 answers
kitchen  every order served at minute 78, 1 pizza wasted
time     0 s
```

The kitchen is plain code and a value in the state of the graph, which only the tools change.
`SoloChef.kt` is the loop of an agent written by hand, a `chef` node that asks the model and a
`kitchen` node that runs its tool calls in order, because these tools change the state of the run.
It is written by hand because a shift goes on when the chef calls no tool, until the kitchen closes.
For tools that change the state, a count of the tokens and a token budget without that, `toolLoop`
has [`stateTools`, `countTokens` and `stopWhen`](guides/agents-with-tools.md).
`Line.kt` is a clock: a `bell` node starts a minute, the three stations run in parallel, and a
`clock` node moves the kitchen to the next minute.

For a complete app, see [telar-demo](https://github.com/deeptelar/telar-demo),
the source of the [Pixel Pizza game](https://deeptelar.github.io/telar-demo/). It is a
Compose Multiplatform app for the browser (Kotlin/Wasm), the desktop and Android, and it uses this
library from Maven Central.
