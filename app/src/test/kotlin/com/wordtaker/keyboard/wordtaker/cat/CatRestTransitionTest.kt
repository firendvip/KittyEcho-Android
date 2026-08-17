package com.wordtaker.keyboard.wordtaker.cat

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class CatRestTransitionTest : FunSpec({

    test("silence after the existing voice hold enters sleep without a settle phase") {
        shouldSleepImmediately(active = true, wantsWalk = false) shouldBe true
    }

    test("recording stop enters sleep immediately even if the cat was walking") {
        shouldSleepImmediately(active = false, wantsWalk = true) shouldBe true
    }

    test("active voice and processing continue walking") {
        shouldSleepImmediately(active = true, wantsWalk = true) shouldBe false
    }

    test("sleep cat and Z glyphs preserve the PC demo pixel source of truth") {
        PcSleepDemoContract.catWidthDemoPx shouldBe 36f
        PcSleepDemoContract.catHeightDemoPx shouldBe 20f
        PcSleepDemoContract.headFromLeftDemoPx shouldBe 9f
        PcSleepDemoContract.outwardDirection shouldBe -1f
        PcSleepDemoContract.zzzPeriodMs shouldBe 3_000f

        sleepZzzSpecs.map { it.sizeDemoPx } shouldContainExactly listOf(8f, 10f, 12f)
        sleepZzzSpecs.map { it.delayMs } shouldContainExactly listOf(0f, 700f, 1_400f)
        sleepZzzSpecs.map { it.outwardOffsetDemoPx } shouldContainExactly listOf(4f, 8f, 12f)
        sleepZzzSpecs.map { it.bottomDemoPx } shouldContainExactly listOf(22f, 25f, 28f)
        sleepZzzSpecs.map { it.sizeDemoPx / PcSleepDemoContract.catHeightDemoPx } shouldContainExactly
            listOf(0.4f, 0.5f, 0.6f)
    }

    test("demo pixel text measurement cancels Android density and font scale") {
        listOf(8f, 10f, 12f).forEach { sizeDemoPx ->
            val sizeSp = demoPxToSp(
                sizeDemoPx = sizeDemoPx,
                density = 3f,
                fontScale = 1.25f,
            )
            (abs(sizeSp * 3f * 1.25f - sizeDemoPx) <= 0.00001f) shouldBe true
        }
        demoPxToSp(sizeDemoPx = 8f, density = 0f, fontScale = 1f) shouldBe 8f
        demoPxToSp(sizeDemoPx = 8f, density = Float.NaN, fontScale = 1f) shouldBe 8f
    }

    test("Z keyframes retain fixed outward rise scale and twenty five percent reveal") {
        sleepZzzAnimationFrame(progress = 0f) shouldBe SleepZzzAnimationFrame(
            translateXDemoPx = 0f,
            translateYDemoPx = 1f,
            scale = 0.85f,
            alpha = 0f,
        )
        val quarter = sleepZzzAnimationFrame(progress = 0.25f)
        quarter.translateXDemoPx shouldBe -0.75f
        quarter.translateYDemoPx shouldBe -0.5f
        (abs(quarter.scale - 0.8875f) <= 0.00001f) shouldBe true
        quarter.alpha shouldBe 1f
        sleepZzzAnimationFrame(progress = 1f) shouldBe SleepZzzAnimationFrame(
            translateXDemoPx = -3f,
            translateYDemoPx = -5f,
            scale = 1f,
            alpha = 0f,
        )
    }
})
