package com.wordtaker.keyboard.wordtaker.network

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ValidatedInternetConnectionTest : FunSpec({

    test("an INTERNET capable network may try the HTTPS backend even when Android validation is partial") {
        val cases = listOf(
            ActiveNetworkState(hasInternet = false, isValidated = false) to false,
            ActiveNetworkState(hasInternet = true, isValidated = false) to true,
            ActiveNetworkState(hasInternet = false, isValidated = true) to false,
            ActiveNetworkState(hasInternet = true, isValidated = true) to true,
        )

        cases.forEach { (state, expected) ->
            val connection = ValidatedInternetConnection { state }
            connection.isAvailable() shouldBe expected
        }
    }

    test("missing service active network or capabilities is safely offline") {
        ValidatedInternetConnection { null }.isAvailable() shouldBe false
    }

    test("permission or platform failures are safely offline") {
        listOf(
            SecurityException("network permission unavailable"),
            IllegalStateException("connectivity service unavailable"),
        ).forEach { failure ->
            val connection = ValidatedInternetConnection { throw failure }
            connection.isAvailable() shouldBe false
        }
    }
})
