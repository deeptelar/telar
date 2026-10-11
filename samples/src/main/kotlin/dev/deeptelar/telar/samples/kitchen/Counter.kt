package dev.deeptelar.telar.samples.kitchen

import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.Description
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.ToolSpec
import dev.deeptelar.telar.agent.execute
import kotlinx.serialization.Serializable

@Serializable
data class NewPizza(
    @Description("Every topping of the pizza. Name a topping twice for extra of it.") val toppings: List<Topping>,
)

@Serializable
data class PizzaName(
    @Description("The name of the pizza, for example \"P1\"") val pizza: String,
)

@Serializable
data class Serving(
    @Description("The number of the order, for example 3") val order: Int,
    @Description("The name of the pizza, for example \"P1\"") val pizza: String,
)

@Serializable
data class Waiting(
    @Description("How long to wait, 1 to ${Kitchen.MAX_WAIT} minutes") val minutes: Int,
)

/**
 * A chef's hands: the tools of the kitchen, which act on [kitchen] and replace it with the kitchen
 * after the action.
 *
 * A counter lives for one turn of one chef. The kitchen itself is a value in the state of the
 * graph, so a checkpoint of the run holds the whole shift.
 *
 * @param only the names of the tools this chef has. The default is every tool.
 */
class Counter(
    var kitchen: Kitchen,
    only: Set<String>? = null,
) {
    private val actions = mutableListOf<(Kitchen) -> Acted>()
    private val reports = mutableListOf<Pair<Int, String>>()

    /**
     * What the chef did, in order: each action that the kitchen accepted. Running them again on a
     * kitchen with the same clock does the same, which is how a chef's turn is added to a kitchen
     * that another chef changed in the same minutes.
     */
    val done: List<(Kitchen) -> Acted> get() = actions

    /** What the chef was told about each action in [done], with the minute the action ended. */
    val told: List<Pair<Int, String>> get() = reports

    val tools: List<Tool> =
        listOf(
            Tool<Unit>("read_orders", "Returns the open orders. Costs no time.") { kitchen.readOrders() },
            Tool<Unit>("check_oven", "Returns the pizzas in the oven and how long each has been there. Costs no time.") {
                kitchen.checkOven()
            },
            Tool<NewPizza>(
                "make_pizza",
                "Makes a raw pizza from dough and toppings, puts it on the counter and returns its name.",
            ) { input ->
                act { it.makePizza(input.toppings) }
            },
            Tool<PizzaName>("bake", "Puts a raw pizza from the counter into the oven.") { input -> act { it.bake(input.pizza) } },
            Tool<PizzaName>("take_out", "Takes a pizza out of the oven. Returns whether it is baked, raw or burnt.") { input ->
                act { it.takeOut(input.pizza) }
            },
            Tool<Serving>("serve", "Serves a pizza that is out of the oven to the table of an order. This closes the order.") { input ->
                act { it.serve(input.order, input.pizza) }
            },
            Tool<Waiting>("wait", "Does nothing for some minutes, for example until a pizza is baked.") { input ->
                act { it.waitFor(input.minutes) }
            },
        ).filter { only == null || it.spec.name in only }

    /** Runs one call. A call the kitchen cannot do comes back as an error result and changes nothing. */
    suspend fun run(call: ToolCall): ChatMessage.ToolResult = tools.execute(listOf(call)).single()

    /**
     * Does [action] on the kitchen and returns what the chef is told.
     *
     * @throws KitchenException if the kitchen cannot do it. The kitchen is then unchanged.
     */
    fun act(action: (Kitchen) -> Acted): String {
        val acted = action(kitchen)
        kitchen = acted.kitchen
        actions += action
        reports += acted.kitchen.minute to acted.told
        return acted.told
    }
}

/** What a model is told about the tools of the kitchen. */
val kitchenTools: List<ToolSpec> = Counter(Kitchen(emptyList(), dough = 0, stock = emptyMap(), closesAt = 0)).tools.map { it.spec }
