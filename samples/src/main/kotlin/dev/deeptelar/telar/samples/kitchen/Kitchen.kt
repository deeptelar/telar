package dev.deeptelar.telar.samples.kitchen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What goes on a pizza. The serial names are the words the model reads and writes. */
@Serializable
enum class Topping {
    @SerialName("tomato")
    TOMATO,

    @SerialName("mozzarella")
    MOZZARELLA,

    @SerialName("basil")
    BASIL,

    @SerialName("pepperoni")
    PEPPERONI,

    @SerialName("mushrooms")
    MUSHROOMS,

    @SerialName("peppers")
    PEPPERS,

    @SerialName("onions")
    ONIONS,
    ;

    val word: String get() = name.lowercase()
}

/** How an order ended. An order that is still [OPEN] when the kitchen closes was missed. */
enum class Outcome { OPEN, ON_TIME, LATE, WRONG, ALLERGY, CANCELLED }

/**
 * One order of a shift, for one pizza.
 *
 * @property placedAt the minute the order reaches the kitchen. The chef cannot see it before.
 * @property dueAt the last minute the pizza can be served for full points.
 * @property text what the guest said. This is all the chef is told.
 * @property wanted the toppings that [text] means, with a topping twice for extra. The chef never sees it.
 * @property never toppings the guest is allergic to.
 */
data class Order(
    val number: Int,
    val table: Int,
    val placedAt: Int,
    val dueAt: Int,
    val text: String,
    val wanted: List<Topping>,
    val never: Set<Topping> = emptySet(),
    val outcome: Outcome = Outcome.OPEN,
) {
    val ticket: String get() = "#$number, table $table, due at minute $dueAt: $text"
}

/**
 * Something a guest decides after ordering, at minute [at]: another pizza ([wanted] and [text]),
 * or no pizza at all when [wanted] is `null`.
 */
data class Change(
    val at: Int,
    val order: Int,
    val text: String,
    val wanted: List<Topping>? = null,
)

enum class Place { COUNTER, OVEN, PASS, SERVED }

/**
 * A pizza the chef made.
 *
 * @property inOvenSince the minute it went into the oven, while it is there.
 * @property baked the minutes it spent in the oven, once it is out.
 */
data class Pizza(
    val name: String,
    val toppings: List<Topping>,
    val place: Place = Place.COUNTER,
    val inOvenSince: Int? = null,
    val baked: Int? = null,
) {
    /** `true` when the pizza came out of the oven neither raw nor burnt. */
    val good: Boolean get() = baked != null && baked in Kitchen.BAKED_AFTER..Kitchen.BURNT_AFTER

    fun has(wanted: List<Topping>): Boolean = toppings.sorted() == wanted.sorted()
}

/** The kitchen after an action, and what the chef is told about it. */
data class Acted(
    val kitchen: Kitchen,
    val told: String,
)

/** An action the kitchen cannot do. Its message is what the chef is told. */
class KitchenException(
    message: String,
) : IllegalStateException(message)

/** How a shift went. */
data class Score(
    val onTime: Int,
    val late: Int,
    val wrong: Int,
    val allergy: Int,
    val missed: Int,
    val cancelled: Int,
    val wasted: Int,
) {
    /** The orders that were not cancelled. */
    val orders: Int get() = onTime + late + wrong + allergy + missed
    val points: Int get() = onTime * ON_TIME_POINTS + late * LATE_POINTS - allergy * ALLERGY_PENALTY

    companion object {
        const val ON_TIME_POINTS = 100
        const val LATE_POINTS = 50
        const val ALLERGY_PENALTY = 100
    }
}

/**
 * A pizzeria during one shift, as a value: every action returns the kitchen after it.
 *
 * Nothing here knows about models or graphs. Time passes only through the actions, each of which
 * costs a fixed number of minutes, so the same actions always give the same shift.
 *
 * @property orders every order of the shift, also the ones that are not placed yet.
 * @property changes what guests will decide later.
 * @property news what happened since the chef was last told: new orders, changes, cancellations.
 */
data class Kitchen(
    val orders: List<Order>,
    val changes: List<Change> = emptyList(),
    val dough: Int,
    val stock: Map<Topping, Int>,
    val closesAt: Int,
    val minute: Int = 0,
    val pizzas: List<Pizza> = emptyList(),
    val news: List<String> = emptyList(),
) {
    /** The orders the chef can see and has not served. */
    val rail: List<Order> get() = orders.filter { it.placedAt <= minute && it.outcome == Outcome.OPEN }

    val oven: List<Pizza> get() = pizzas.filter { it.place == Place.OVEN }

    /** `true` when the kitchen is closed, or no order is left to serve. */
    val over: Boolean get() = minute >= closesAt || orders.none { it.outcome == Outcome.OPEN }

    val stockList: String get() = "$dough dough, " + Topping.entries.joinToString { "${stock[it] ?: 0} ${it.word}" }

    /** The kitchen when the shift starts, with the orders that are already there as [news]. */
    fun opened(): Kitchen = copy(news = orders.filter { it.placedAt <= minute }.map { "New order ${it.ticket}" })

    /** The kitchen [minutes] later: orders that were placed and changes that were made meanwhile become [news]. */
    fun after(minutes: Int): Kitchen {
        val now = minute + minutes
        val placed = orders.filter { it.placedAt in (minute + 1)..now }.map { "New order ${it.ticket}" }
        val decided = changes.filter { it.at in (minute + 1)..now && order(it.order)?.outcome == Outcome.OPEN }
        val changed =
            orders.map { order ->
                val change = decided.lastOrNull { it.order == order.number } ?: return@map order
                if (change.wanted ==
                    null
                ) {
                    order.copy(outcome = Outcome.CANCELLED)
                } else {
                    order.copy(text = change.text, wanted = change.wanted)
                }
            }
        val told =
            decided.map {
                if (it.wanted ==
                    null
                ) {
                    "Order #${it.order} is cancelled: ${it.text}"
                } else {
                    "Order #${it.order} changed: ${it.text}"
                }
            }
        return copy(minute = now, orders = changed, news = news + placed + told)
    }

    /** The chef has been told the [news]. */
    fun told(): Kitchen = copy(news = emptyList())

    fun readOrders(): String = if (rail.isEmpty()) "No open orders." else rail.joinToString("\n") { it.ticket }

    fun checkOven(): String =
        if (oven.isEmpty()) {
            "The oven is empty."
        } else {
            oven.joinToString("\n") { "${it.name} has been in the oven for ${minute - it.inOvenSince!!} minutes." }
        }

    fun makePizza(toppings: List<Topping>): Acted {
        open()
        if (toppings.isEmpty()) fail("A pizza needs at least one topping.")
        if (dough == 0) fail("There is no dough left.")
        val needed = toppings.groupingBy { it }.eachCount()
        needed.forEach { (topping, count) ->
            val left = stock[topping] ?: 0
            if (left < count) fail("There is not enough ${topping.word}: $left left.")
        }
        val pizza = Pizza("P${pizzas.size + 1}", toppings)
        val used = stock.mapValues { (topping, left) -> left - (needed[topping] ?: 0) }
        return Acted(
            copy(dough = dough - 1, stock = used, pizzas = pizzas + pizza).after(MAKE_MINUTES),
            "${pizza.name} is on the counter: ${toppings.joinToString { it.word }}.",
        )
    }

    fun bake(name: String): Acted {
        open()
        val pizza = pizza(name)
        if (pizza.place !=
            Place.COUNTER
        ) {
            fail("${pizza.name} is not on the counter. Only a pizza that was never baked can go into the oven.")
        }
        if (oven.size == OVEN_SLOTS) fail("The oven is full: ${oven.joinToString { it.name }}.")
        val later = after(MOVE_MINUTES)
        return Acted(
            later.with(pizza.copy(place = Place.OVEN, inOvenSince = later.minute)),
            "${pizza.name} is in the oven. It is baked from minute ${later.minute + BAKED_AFTER} and burnt after minute ${later.minute + BURNT_AFTER}.",
        )
    }

    fun takeOut(name: String): Acted {
        open()
        val pizza = pizza(name)
        if (pizza.place != Place.OVEN) fail("${pizza.name} is not in the oven.")
        val out = pizza.copy(place = Place.PASS, inOvenSince = null, baked = minute - pizza.inOvenSince!!)
        val how =
            when {
                out.good -> "baked"
                out.baked!! < BAKED_AFTER -> "still raw after ${out.baked} minutes, and it cannot go back in"
                else -> "burnt"
            }
        return Acted(with(out).after(MOVE_MINUTES), "${pizza.name} is out of the oven: $how.")
    }

    fun serve(number: Int, name: String): Acted {
        open()
        val order = order(number)?.takeIf { it.placedAt <= minute } ?: fail("There is no order #$number.")
        if (order.outcome == Outcome.CANCELLED) fail("Order #$number was cancelled.")
        if (order.outcome != Outcome.OPEN) fail("Order #$number was already served.")
        val pizza = pizza(name)
        when (pizza.place) {
            Place.COUNTER -> fail("${pizza.name} was never baked.")
            Place.OVEN -> fail("${pizza.name} is still in the oven.")
            Place.SERVED -> fail("${pizza.name} was already served.")
            Place.PASS -> Unit
        }
        val later = after(MOVE_MINUTES)
        // The guest may have changed the order during the walk to the table.
        val served = later.order(number)!!
        if (served.outcome == Outcome.CANCELLED) return Acted(later, "Order #$number was cancelled on your way to the table.")
        val outcome =
            when {
                pizza.toppings.any { it in served.never } -> Outcome.ALLERGY
                !pizza.good || !pizza.has(served.wanted) -> Outcome.WRONG
                later.minute > served.dueAt -> Outcome.LATE
                else -> Outcome.ON_TIME
            }
        val said =
            when (outcome) {
                Outcome.ALLERGY -> "The guest is allergic to something on it."
                Outcome.WRONG -> if (pizza.good) "It is not what the guest ordered." else "The guest sent it back: it was not baked right."
                Outcome.LATE -> "It was late."
                else -> "On time."
            }
        return Acted(
            later
                .with(pizza.copy(place = Place.SERVED))
                .copy(orders = later.orders.map { if (it.number == number) it.copy(outcome = outcome) else it }),
            "${pizza.name} was served to table ${served.table} for order #$number. $said",
        )
    }

    fun waitFor(minutes: Int): Acted {
        open()
        if (minutes !in 1..MAX_WAIT) fail("Wait for 1 to $MAX_WAIT minutes.")
        return Acted(after(minutes), "You waited $minutes minutes.")
    }

    fun score(): Score {
        fun count(outcome: Outcome) = orders.count { it.outcome == outcome }
        val used = count(Outcome.ON_TIME) + count(Outcome.LATE)
        return Score(
            onTime = count(Outcome.ON_TIME),
            late = count(Outcome.LATE),
            wrong = count(Outcome.WRONG),
            allergy = count(Outcome.ALLERGY),
            missed = count(Outcome.OPEN),
            cancelled = count(Outcome.CANCELLED),
            wasted = pizzas.size - used,
        )
    }

    private fun order(number: Int): Order? = orders.firstOrNull { it.number == number }

    private fun pizza(name: String): Pizza =
        pizzas.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: fail("There is no pizza $name.")

    private fun with(pizza: Pizza): Kitchen = copy(pizzas = pizzas.map { if (it.name == pizza.name) pizza else it })

    private fun open() {
        if (over) fail(if (minute >= closesAt) "The kitchen is closed." else "Every order is served. The shift is over.")
    }

    private fun fail(message: String): Nothing = throw KitchenException(message)

    companion object {
        /** The minutes one answer of the chef costs, whatever it does. */
        const val THINK_MINUTES = 1
        const val MAKE_MINUTES = 2

        /** The minutes it costs to carry a pizza: into the oven, out of it, or to a table. */
        const val MOVE_MINUTES = 1
        const val OVEN_SLOTS = 2
        const val BAKED_AFTER = 6
        const val BURNT_AFTER = 12
        const val MAX_WAIT = 15
    }
}
