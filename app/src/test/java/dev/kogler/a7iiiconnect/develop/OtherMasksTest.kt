package dev.kogler.a7iiiconnect.develop

import dev.kogler.a7iiiconnect.develop.engine.Mask
import dev.kogler.a7iiiconnect.develop.engine.P
import dev.kogler.a7iiiconnect.develop.engine.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherMasksTest {
    /** An edit from the computer, with masks this editor doesn't know. */
    private val fromComputer = """
        v=2
        profile=COLOR
        exposure=0.3
        ai.denoise=60.0
        mask.0=sky;exposure=-0.5
        mask.1=object;id=8970b9f85445;picks=0.846_0.576_1;grain=1;exposure=2.06
        mask.2=depth;x0=0.6;x1=1.0;feather=30.0;saturation=-20.0
    """.trimIndent()

    @Test
    fun masksAndValuesFromTheComputerSurviveAnEditHere() {
        val s = Settings.decode(fromComputer)
        assertEquals(1, s.masks.size)
        assertEquals(2, s.otherMasks.size)
        val edited = s.with(P.EXPOSURE, 0.6f).copy(masks = s.masks + Mask.subject())
        val text = edited.encode()
        assertTrue(text, "mask.2=object;id=8970b9f85445;picks=0.846_0.576_1;grain=1;exposure=2.06" in text)
        assertTrue(text, "mask.3=depth;x0=0.6;x1=1.0;feather=30.0;saturation=-20.0" in text)
        assertTrue(text, "ai.denoise=60.0" in text)
        assertEquals(edited, Settings.decode(text))
    }
}
