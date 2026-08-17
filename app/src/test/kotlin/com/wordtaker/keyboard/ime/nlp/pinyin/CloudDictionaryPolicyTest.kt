package com.wordtaker.keyboard.ime.nlp.pinyin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class CloudDictionaryPolicyTest : FunSpec({

    fun allowedEnvironment(
        providerId: String? = PinyinLanguageProvider.ProviderId,
        composing: String = "ni'hao",
        cloudEnabled: Boolean = true,
        hasValidatedInternet: Boolean = true,
        incognito: Boolean = false,
        editorType: CloudEditorType = CloudEditorType.NORMAL,
        noPersonalizedLearning: Boolean = false,
        englishMode: Boolean = false,
    ) = CloudDictionaryEnvironment(
        activeSuggestionProviderId = providerId,
        composingText = composing,
        cloudEnabled = cloudEnabled,
        hasValidatedInternet = hasValidatedInternet,
        isIncognito = incognito,
        editorType = editorType,
        noPersonalizedLearning = noPersonalizedLearning,
        isEnglishMode = englishMode,
    )

    test("full-pinyin normalization shares local rules and sends letters only") {
        normalizeFullPinyin("Xi'An") shouldBe NormalizedFullPinyin(
            local = "xi'an",
            cloud = "xian",
        )
        normalizeFullPinyin("滑行").shouldBeNull()
        normalizeFullPinyin("xi3an").shouldBeNull()
        normalizeFullPinyin("xi-an").shouldBeNull()
        normalizeFullPinyin("xi an").shouldBeNull()
        normalizeFullPinyin("'''").shouldBeNull()
        normalizeFullPinyin("").shouldBeNull()
    }

    test("only active full-pinyin provider allows typed or glide-produced legal composing") {
        allowedEnvironment(composing = "nihao").allows("nihao") shouldBe true
        allowedEnvironment(composing = "zhong'guo").allows("zhongguo") shouldBe true

        listOf(
            ShuangpinLanguageProvider.ProviderId,
            T9LanguageProvider.ProviderId,
            "org.florisboard.nlp.providers.latin",
            "org.florisboard.nlp.providers.handwriting",
            "org.florisboard.nlp.providers.han-shape-based",
            null,
        ).forEach { provider ->
            allowedEnvironment(providerId = provider).allows("nihao") shouldBe false
        }
    }

    test("cloud switch validated internet incognito and English mode independently fail closed") {
        allowedEnvironment(cloudEnabled = false).allows("nihao") shouldBe false
        allowedEnvironment(hasValidatedInternet = false).allows("nihao") shouldBe false
        allowedEnvironment(incognito = true).allows("nihao") shouldBe false
        allowedEnvironment(englishMode = true).allows("nihao") shouldBe false
    }

    test("password visible-password web-password and no-personalized-learning all fail closed") {
        listOf(
            CloudEditorType.PASSWORD,
            CloudEditorType.VISIBLE_PASSWORD,
            CloudEditorType.WEB_PASSWORD,
        ).forEach { type ->
            allowedEnvironment(editorType = type).allows("nihao") shouldBe false
        }
        allowedEnvironment(noPersonalizedLearning = true).allows("nihao") shouldBe false
        allowedEnvironment(
            incognito = false,
            noPersonalizedLearning = true,
        ).allows("nihao") shouldBe false
    }

    test("response gate compares the same letters-only cloud value and rejects changed invalid or cleared text") {
        allowedEnvironment(composing = "Xi'An").allows("xian") shouldBe true
        allowedEnvironment(composing = "nih").allows("nihao") shouldBe false
        allowedEnvironment(composing = "ni3hao").allows("nihao") shouldBe false
        allowedEnvironment(composing = "ni-hao").allows("nihao") shouldBe false
        allowedEnvironment(composing = "").allows("nihao") shouldBe false
    }
})
