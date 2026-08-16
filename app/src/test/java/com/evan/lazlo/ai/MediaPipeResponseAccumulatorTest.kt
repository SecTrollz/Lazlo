package com.evan.lazlo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPipeResponseAccumulatorTest {

    @Test
    fun `non-empty chunks forward as Emit, including a non-final empty chunk`() {
        val accumulator = MediaPipeResponseAccumulator()

        val first = accumulator.onProgress("Hel", done = false)
        val second = accumulator.onProgress("lo", done = false)
        // MediaPipe's own sync cadence can hand back an empty chunk mid-stream
        // (e.g. a decode step that produced nothing new yet); that's not the
        // "generation ended with nothing" case and must still just forward.
        val third = accumulator.onProgress("", done = false)

        assertEquals(MediaPipeResponseAccumulator.Action.Emit(ChatToken("Hel", isFinal = false)), first)
        assertEquals(MediaPipeResponseAccumulator.Action.Emit(ChatToken("lo", isFinal = false)), second)
        assertEquals(MediaPipeResponseAccumulator.Action.Emit(ChatToken("", isFinal = false)), third)
    }

    @Test
    fun `a done chunk after real text arrived still emits, even if the final chunk itself is empty`() {
        val accumulator = MediaPipeResponseAccumulator()
        accumulator.onProgress("hi", done = false)

        val result = accumulator.onProgress("", done = true)

        assertEquals(MediaPipeResponseAccumulator.Action.Emit(ChatToken("", isFinal = true)), result)
    }

    @Test
    fun `a done chunk that is itself the first real text still emits`() {
        val accumulator = MediaPipeResponseAccumulator()

        val result = accumulator.onProgress("ok", done = true)

        assertEquals(MediaPipeResponseAccumulator.Action.Emit(ChatToken("ok", isFinal = true)), result)
    }

    @Test
    fun `generation finishing without ever producing text fails instead of completing silently`() {
        val accumulator = MediaPipeResponseAccumulator()
        accumulator.onProgress("", done = false)
        accumulator.onProgress("", done = false)

        val result = accumulator.onProgress("", done = true)

        assertTrue(result is MediaPipeResponseAccumulator.Action.Fail)
        val error = (result as MediaPipeResponseAccumulator.Action.Fail).error
        assertTrue(error is IllegalStateException)
        assertTrue(error.message!!.contains("produced no text"))
    }

    @Test
    fun `immediate done with no prior chunks and empty text fails`() {
        val accumulator = MediaPipeResponseAccumulator()

        val result = accumulator.onProgress("", done = true)

        assertTrue(result is MediaPipeResponseAccumulator.Action.Fail)
    }
}
