# Decision models

A chat model writes. A **decision model** decides: it picks one of the options you give it, answers
yes or no, or rates on a scale, and says how sure it is. Its answer has a fixed type, and a model
built for deciding gives it in a fraction of the time and the price of a chat model. That fits the
places where a graph decides: which node runs next, whether a draft is good enough, whether a
person has to look.

`telar-agent` has the interface, `DecisionModel`, and two implementations to choose from:

```kotlin
// Any chat model you already use decides (telar-agent). A small, fast model is usually enough.
val decider: DecisionModel = ChatDecisionModel(AnthropicChatModel(HttpClient(), apiKey = key, model = "claude-haiku-5-5"))

// Jev of TypeSafe AI, a model built for decisions, on every platform (telar-typesafe).
val jev: DecisionModel = TypeSafeDecisionModel(HttpClient(), apiKey = key)
```

`ChatDecisionModel` asks the chat model how likely it finds each option, in one call for all the
questions of a request, and computes `confidence` from those numbers as Jev does. The numbers are
the model's own estimate, so its `confidence` is rougher than that of
[Jev](https://docs.typesafe.ai), a model trained to decide. The examples below use `jev`; every one
of them works with `decider` as well.

`decisionEdge` lets the model pick the next node. This is the [quick start](../quick-start.md) without `categoryOf`:

```kotlin
val graph = StateGraph<SupportEmail> {
    val refund = node("refund") { email -> email.copy(reply = "Your refund is on its way.") }
    val technical = node("technical") { email -> email.copy(reply = "Please update the app.") }
    val escalate = node("escalate") { email -> email.copy(reply = "A colleague will reply today.") }

    decisionEdge(
        from = START,
        model = jev,
        instructions = "What does the customer who wrote this support email want?",
        // The nodes the model may pick. It sees their names and these descriptions.
        routes = mapOf(
            refund to "Money back for an order or a charge",
            technical to "Help with something that does not work",
            // An option for the rest: a model picks one of its options, also when none fits.
            escalate to "A complaint, or anything else that a person should read",
        ),
        // An email the model is not sure about goes to a person too.
        minConfidence = 0.6,
        fallback = escalate,
    ) { email -> email.body } // what the model judges
}.compile()
```

Runnable version: [`DecisionRouter`](https://github.com/deeptelar/telar/blob/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/DecisionRouter.kt).
It runs without an API key, with Jev when `TYPESAFE_API_KEY` is set, and with a chat model through
`ChatDecisionModel` when the key of one is set, such as `OPENAI_API_KEY`.

In a node, ask one question with `choose`, `isYes` or `score`, and keep the answer in the state:

```kotlin
enum class Team(val handles: String) { REFUND("Money back"), TECHNICAL("Something does not work") }

val classify = node("classify", work = { email ->
    jev.choose<Team>(email.body, "Which team should handle this email?") { it.handles }
}) { email, answer -> email.copy(team = answer.value<Team>(), confidence = answer.confidence) }

val urgent = jev.isYes(email.body, "Does the customer need an answer today?").probability > 0.8
val anger = jev.score(email.body, "How angry is the customer?", listOf("Calm", "Annoyed", "Furious")).score
```

- **`confidence` says how clearly one option won**: how far the most likely option is above an even
  split, from 0 to 1. It does not say that the answer is right. Try your limits on your own data
  before you rely on them.
- **Give the model an option for the rest.** It picks one of the options it has, also when none
  fits, and it can be sure of that pick. Asked to choose between `refund` and `technical` only, Jev
  gave "This is unacceptable, third time I write to you!!" to `technical` with a confidence of 0.9.
- `decisionEdge` does not write the decision into the state. When the state should keep it, ask in a
  node as above, and route with `conditionalEdge` on what the node stored.
- Several questions about the same text cost one call: `jev.decide(DecisionRequest(text, questions))`.
- A failed call throws `DecisionModelException`. On an edge it is the `cause` of an
  `EdgeConditionException`.
- A call to `TypeSafeDecisionModel` may take one minute. Change that with `timeout`.
- In a test, a lambda is a model: `DecisionModel { request -> DecisionResponse(request.questions.mapValues { Answer.Choice("refund") }) }`.
  `ScriptedDecisionModel { chooses("refund"); no() }` of `telar-test` gives one decision after the
  other: see [Testing](testing.md).
