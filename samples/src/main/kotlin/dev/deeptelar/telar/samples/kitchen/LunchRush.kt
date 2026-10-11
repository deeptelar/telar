package dev.deeptelar.telar.samples.kitchen

import dev.deeptelar.telar.samples.kitchen.Topping.BASIL
import dev.deeptelar.telar.samples.kitchen.Topping.MOZZARELLA
import dev.deeptelar.telar.samples.kitchen.Topping.MUSHROOMS
import dev.deeptelar.telar.samples.kitchen.Topping.ONIONS
import dev.deeptelar.telar.samples.kitchen.Topping.PEPPERONI
import dev.deeptelar.telar.samples.kitchen.Topping.PEPPERS
import dev.deeptelar.telar.samples.kitchen.Topping.TOMATO

/** The menu, as the chef is told it. */
val MENU: Map<String, List<Topping>> =
    mapOf(
        "margherita" to listOf(TOMATO, MOZZARELLA, BASIL),
        "pepperoni" to listOf(TOMATO, MOZZARELLA, PEPPERONI),
        "funghi" to listOf(TOMATO, MOZZARELLA, MUSHROOMS),
        "veggie" to listOf(TOMATO, MOZZARELLA, MUSHROOMS, PEPPERS, ONIONS),
    )

/**
 * The shift every chef is measured on: twelve orders in 95 minutes. It is the same every time, so
 * that the result of one model can be compared with the result of another.
 *
 * It is not twelve times the same job:
 * - #2 says "cheese" for the mozzarella, and #4 swaps one topping for extra of another.
 * - Table 3 says with its first order what nobody there may get. Its second order, half an hour
 *   later, is a pizza that has it, and does not say so again.
 * - Table 5 adds to its order three minutes later, in words that only make sense with the order.
 *   Table 6 leaves.
 * - #7 says in passing what the guests do not want, which is nothing to act on.
 * - #7 to #10 arrive together for an oven with two places, and the first on the rail is due last.
 * - #9 names no pizza. It refers to an order that was served long before, and takes part of it away.
 * - #11 is the pizza that table 6 never got.
 * - The mushrooms are enough for the orders and for nothing else.
 */
fun lunchRush(): Kitchen =
    Kitchen(
        orders =
            listOf(
                Order(1, table = 1, placedAt = 0, dueAt = 20, text = "One margherita.", wanted = MENU.getValue("margherita")),
                Order(
                    2,
                    table = 2,
                    placedAt = 0,
                    dueAt = 24,
                    text = "A pepperoni with extra cheese.",
                    wanted = listOf(TOMATO, MOZZARELLA, MOZZARELLA, PEPPERONI),
                ),
                Order(
                    3,
                    table = 3,
                    placedAt = 8,
                    dueAt = 30,
                    text = "A funghi. A note for the whole shift: nobody at table 3 may get onions, they are allergic.",
                    wanted = MENU.getValue("funghi"),
                    never = setOf(ONIONS),
                ),
                Order(
                    4,
                    table = 4,
                    placedAt = 14,
                    dueAt = 36,
                    text = "A veggie with extra peppers instead of the onions.",
                    wanted = listOf(TOMATO, MOZZARELLA, MUSHROOMS, PEPPERS, PEPPERS),
                ),
                Order(
                    5,
                    table = 5,
                    placedAt = 20,
                    dueAt = 44,
                    text = "A pepperoni without tomato.",
                    wanted = listOf(MOZZARELLA, PEPPERONI),
                ),
                Order(6, table = 6, placedAt = 26, dueAt = 50, text = "A margherita.", wanted = MENU.getValue("margherita")),
                Order(
                    7,
                    table = 7,
                    placedAt = 40,
                    dueAt = 76,
                    text = "One veggie. They ask how long it takes, and no, they do not want extra cheese.",
                    wanted = MENU.getValue("veggie"),
                ),
                Order(8, table = 8, placedAt = 40, dueAt = 58, text = "A pepperoni.", wanted = MENU.getValue("pepperoni")),
                Order(
                    9,
                    table = 9,
                    placedAt = 40,
                    dueAt = 64,
                    text = "The same as table 2 had, but without the extra.",
                    wanted = MENU.getValue("pepperoni"),
                ),
                Order(
                    10,
                    table = 3,
                    placedAt = 40,
                    dueAt = 70,
                    text = "Another pizza for table 3: a veggie.",
                    wanted = listOf(TOMATO, MOZZARELLA, MUSHROOMS, PEPPERS),
                    never = setOf(ONIONS),
                ),
                Order(11, table = 11, placedAt = 60, dueAt = 82, text = "A margherita.", wanted = MENU.getValue("margherita")),
                Order(
                    12,
                    table = 12,
                    placedAt = 66,
                    dueAt = 90,
                    text = "Funghi with extra mushrooms.",
                    wanted = listOf(TOMATO, MOZZARELLA, MUSHROOMS, MUSHROOMS),
                ),
            ),
        changes =
            listOf(
                Change(at = 23, order = 5, text = "Table 5 also wants mushrooms on it.", wanted = listOf(MOZZARELLA, PEPPERONI, MUSHROOMS)),
                Change(at = 30, order = 6, text = "the guests of table 6 left."),
            ),
        dough = 14,
        stock =
            mapOf(
                TOMATO to 20,
                MOZZARELLA to 20,
                BASIL to 8,
                PEPPERONI to 8,
                MUSHROOMS to 7,
                PEPPERS to 5,
                ONIONS to 4,
            ),
        closesAt = 95,
    )
