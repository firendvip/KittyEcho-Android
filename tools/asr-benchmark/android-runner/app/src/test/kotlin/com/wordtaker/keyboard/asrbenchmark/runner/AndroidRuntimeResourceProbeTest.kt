package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidRuntimeResourceProbeTest {
    @Test
    fun procStatusParserReportsRssAndAvailableSwapInBytes() {
        val parsed = ProcStatusMemoryParser.parse(
            sequenceOf(
                "Name:\trunner",
                "VmRSS:\t123 kB",
                "VmSwap:\t7 kB",
            ),
        )

        assertEquals(123L * 1024L, parsed.rssBytes)
        assertEquals(7L * 1024L, parsed.swapBytes)
    }

    @Test
    fun procStatusParserOmitsUnavailableSwap() {
        val parsed = ProcStatusMemoryParser.parse(sequenceOf("VmRSS: 1 kB"))

        assertEquals(1024L, parsed.rssBytes)
        assertNull(parsed.swapBytes)
    }

    @Test
    fun procStatusParserRejectsMissingMalformedNegativeAndOverflowValues() {
        listOf(
            emptySequence(),
            sequenceOf("VmRSS: not-a-number kB"),
            sequenceOf("VmRSS: -1 kB"),
            sequenceOf("VmRSS: ${Long.MAX_VALUE} kB"),
            sequenceOf("VmRSS: 1 kB", "VmSwap: invalid kB"),
        ).forEach { invalid ->
            assertThrows(BenchmarkContractException::class.java) {
                ProcStatusMemoryParser.parse(invalid)
            }
        }
    }
}
