package dev.deeptelar.telar.samples.kitchen

// The rules that the chefs without a model work by. They also tell when a chef with a model has something to do.

/**
 * Gives each of [orders], the first before the others, a pizza that is made, is not spoilt, and has
 * the toppings that [toppings] names for the order. An order gets `null` when there is none. A pizza
 * that is nearer to the table is given first.
 */
fun Kitchen.assign(orders: List<Order>, toppings: (Order) -> List<Topping>): List<Pair<Order, Pizza?>> {
    val free = made.filter { it.place != Place.SERVED && (it.baked == null || it.good) }.toMutableList()
    return orders.map { order ->
        val pizza = free.filter { it.has(toppings(order)) }.minByOrNull { NEAREST_FIRST.indexOf(it.place) }
        free.remove(pizza)
        order to pizza
    }
}

private val NEAREST_FIRST = listOf(Place.PASS, Place.OVEN, Place.COUNTER)

/** `true` when the dough and the stock are enough for a pizza with [toppings]. */
fun Kitchen.canMake(toppings: List<Topping>): Boolean =
    dough > 0 && toppings.groupingBy { it }.eachCount().all { (topping, count) -> (stock[topping] ?: 0) >= count }

/** The pizza in the oven that is baked and has been there longest, if any. */
fun Kitchen.baked(): Pizza? = oven.filter { minute - it.inOvenSince!! >= Kitchen.BAKED_AFTER }.minByOrNull { it.inOvenSince!! }
