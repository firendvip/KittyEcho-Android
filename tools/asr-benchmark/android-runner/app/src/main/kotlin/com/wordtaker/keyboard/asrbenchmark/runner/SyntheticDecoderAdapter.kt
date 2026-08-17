package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.CanonicalPcmWav
import com.wordtaker.keyboard.asrbenchmark.core.DecoderAdapter
import com.wordtaker.keyboard.asrbenchmark.core.DecoderResult
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import com.wordtaker.keyboard.asrbenchmark.core.Hashing

class SyntheticDecoderAdapter : DecoderAdapter {
    override fun decode(canonicalPcmWav: ByteArray): DecoderResult {
        CanonicalPcmWav.inspect(canonicalPcmWav)
        return DecoderResult(
            transcript = "synthetic-${Hashing.sha256(canonicalPcmWav).take(12)}",
            status = DecoderStatus.OK,
            errorCode = null,
        )
    }
}
