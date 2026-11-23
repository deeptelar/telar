package dev.deeptelar.telar

import kotlin.test.Test
import kotlin.test.assertEquals

@ExperimentalTelarApi
private fun preview(): String = "preview"

class ExperimentalTelarApiTest {
    // The test source sets opt in to ExperimentalTelarApi and treat warnings as errors, so this
    // file compiles only when the opt-in is set up.
    @Test
    fun testsUseMarkedApiWithoutOptingInThemselves() {
        assertEquals("preview", preview())
    }
}
