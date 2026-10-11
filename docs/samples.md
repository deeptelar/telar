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
./gradlew :samples:runKitchenRush       # a model runs a pizzeria for a shift, on a token budget
./gradlew :samples:runLevel1            # ... runLevel8, the levels of the tutorial
```

## The kitchen: one shift, measured

[`kitchen/`](https://github.com/deeptelar/telar/tree/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/kitchen)
is a larger sample. A model is the only chef of a pizzeria for one shift of twelve orders. It makes,
bakes and serves pizzas with tools, while orders arrive, change and are cancelled, and the oven has
two places. The shift is the same every time, so the result of one model can be compared with the
result of another: first the points for the orders, then the tokens the chef used. The shift ends
when the kitchen closes, when every order is served, or when the token budget is spent.

```bash
./gradlew :samples:runKitchenRush                                         # a scripted chef, no key needed
ANTHROPIC_API_KEY=your-key ./gradlew :samples:runKitchenRush              # Claude
ANTHROPIC_API_KEY=your-key ANTHROPIC_MODEL=claude-haiku-5-5 ./gradlew :samples:runKitchenRush
OPENAI_API_KEY=your-key OPENAI_MODEL=gpt-5-mini TOKEN_BUDGET=100000 ./gradlew :samples:runKitchenRush
```

The model is chosen like the one of `runToolAgent`, see
[Agents with tools](guides/agents-with-tools.md). A shift with a real model asks it many times and
sends the whole conversation each time, so it costs more than the other samples: `TOKEN_BUDGET` sets
the most tokens it may use, and the default is 300000. The run ends with its result:

```text
Lunch rush, solo chef (scripted: no model was asked, and the tokens are a guess)
orders   11 of 11 on time
points   1100 of 1100
tokens   16037 (15633 read, 404 written) in 11 answers
kitchen  every order served at minute 85, 0 pizzas wasted
time     0 s
```

The kitchen is plain code and a value in the state of the graph, which only the tools change. The
graph is the loop of an agent written by hand, a `chef` node that asks the model and a `kitchen`
node that runs its tool calls in order, because these tools change the state of the run.

For a complete app, see [telar-demo](https://github.com/deeptelar/telar-demo),
the source of the [Pixel Pizza game](https://deeptelar.github.io/telar-demo/). It is a
Compose Multiplatform app for the browser (Kotlin/Wasm), the desktop and Android, and it uses this
library from Maven Central.
