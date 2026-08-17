package com.wordtaker.keyboard.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.io.File

class ApkPackagingContractTest : FunSpec({
    test("branded APK is copied from a non-branded canonical output") {
        val buildScript = projectFile("app/build.gradle.kts").readText()

        buildScript.contains("renameTo(dest)").shouldBeFalse()
        buildScript.contains("copyTo(dest, overwrite = true)").shouldBeTrue()
        buildScript.contains("apk != dest").shouldBeTrue()
        buildScript.contains("!apk.name.startsWith(\"KittyEcho-\")").shouldBeTrue()
    }
})

private fun projectFile(relativePath: String): File {
    var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    repeat(8) {
        val candidate = File(current, relativePath)
        if (candidate.isFile) return candidate
        current = current.parentFile ?: error("Could not find project file: $relativePath")
    }
    error("Could not find project file: $relativePath")
}
