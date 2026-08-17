package com.wordtaker.keyboard.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot

/**
 * Installed-icon contract for the user-approved source:
 * /Users/Admin/Desktop/小猫头像/弦外小猫头像-白底.svg
 *
 * The SVG's white canvas belongs to the adaptive background layer. Its cat and star remain exact
 * vector paths in the foreground, so launcher masks do not produce a second rounded white plate.
 */
class LauncherIconResourceContractTest : FunSpec({

    test("all installed variants inherit one foreground and monochrome source") {
        listOf("debug", "beta", "benchmark", "release").forEach { sourceSet ->
            iconOverride(sourceSet, "ic_app_icon_foreground.xml").exists() shouldBe false
            iconOverride(sourceSet, "ic_app_icon_monochrome.xml").exists() shouldBe false
        }
    }

    test("adaptive normal and round icons use white background foreground and monochrome layers") {
        listOf(
            "app/src/main/res/mipmap-anydpi-v26/floris_app_icon.xml",
            "app/src/main/res/mipmap-anydpi-v26/floris_app_icon_round.xml",
        ).forEach { relativePath ->
            val root = xml(relativePath).documentElement
            root.tagName shouldBe "adaptive-icon"
            root.childDrawable("background") shouldBe "@color/ic_app_icon_background"
            root.childDrawable("foreground") shouldBe "@drawable/ic_app_icon_foreground"
            root.childDrawable("monochrome") shouldBe "@drawable/ic_app_icon_monochrome"
        }

        val colors = xml("app/src/main/res/values/colors.xml")
        colors.namedElement("color", "ic_app_icon_background").textContent.trim() shouldBe "#FFFFFF"
    }

    test("foreground exactly preserves the approved SVG artwork without embedding its white rect") {
        val vector = xml("app/src/main/res/drawable/ic_app_icon_foreground.xml")
        val root = vector.documentElement
        root.androidAttr("viewportWidth") shouldBe "1024"
        root.androidAttr("viewportHeight") shouldBe "1024"
        root.androidAttr("width") shouldBe "108dp"
        root.androidAttr("height") shouldBe "108dp"

        val group = vector.singleGroup()
        val paths = vector.paths()
        val pathData = paths.map { it.androidAttr("pathData") }

        // SVG DOM order is paint order: the dark face must be behind both pink inner ears.
        val faceIndex = pathData.indexOf(APPROVED_CAT_FACE_PATH)
        val innerEarIndexes = listOf(
            pathData.indexOf(APPROVED_LEFT_INNER_EAR_PATH),
            pathData.indexOf(APPROVED_RIGHT_INNER_EAR_PATH),
        )
        innerEarIndexes.all { faceIndex in 0 until it } shouldBe true

        pathData shouldBe APPROVED_FOREGROUND_PATHS
        paths.map { it.androidAttr("fillColor") } shouldBe APPROVED_FOREGROUND_COLORS
        paths.map { it.androidAttr("fillAlpha") } shouldBe APPROVED_FOREGROUND_ALPHAS

        assertMaskSafeAndLegible(group, root)
    }

    test("monochrome is the approved cat and star silhouette and remains mask safe") {
        val vector = xml("app/src/main/res/drawable/ic_app_icon_monochrome.xml")
        val root = vector.documentElement
        root.androidAttr("viewportWidth") shouldBe "1024"
        root.androidAttr("viewportHeight") shouldBe "1024"

        val paths = vector.paths()
        paths.map { it.androidAttr("pathData") } shouldBe APPROVED_MONOCHROME_PATHS
        paths.map { it.androidAttr("fillColor") }.distinct() shouldBe listOf("#000000")

        assertMaskSafeAndLegible(vector.singleGroup(), root)
    }

    test("manifest IME picker and splash all resolve through the installed icon chain") {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        manifest.contains("""android:icon="@mipmap/floris_app_icon"""") shouldBe true
        manifest.contains("""android:roundIcon="@mipmap/floris_app_icon_round"""") shouldBe true

        val method = projectFile("app/src/main/res/xml/method.xml").readText()
        method.contains("""android:icon="@mipmap/floris_app_icon"""") shouldBe true

        val themes = projectFile("app/src/main/res/values/themes.xml").readText()
        themes.contains(
            """<item name="windowSplashScreenAnimatedIcon">@drawable/ic_app_icon_foreground</item>""",
        ) shouldBe true
        themes.contains(
            """<item name="windowSplashScreenIconBackgroundColor">@color/ic_app_icon_background</item>""",
        ) shouldBe true
    }
})

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val APPROVED_CAT_FACE_PATH =
    "M175,587 A336.5,300 0,1 0,848 587 A336.5,300 0,1 0,175 587 Z"
private const val APPROVED_LEFT_INNER_EAR_PATH = "M358,197 L429,289 L332,335 Z"
private const val APPROVED_RIGHT_INNER_EAR_PATH = "M665,197 L691,335 L594,289 Z"

private val APPROVED_FOREGROUND_PATHS = listOf(
    "M311,117 L515,340 L246,420 Z",
    "M712,117 L777,420 L508,340 Z",
    APPROVED_CAT_FACE_PATH,
    APPROVED_LEFT_INNER_EAR_PATH,
    APPROVED_RIGHT_INNER_EAR_PATH,
    "M325,560.5 A71,90.5 0,1 0,467 560.5 A71,90.5 0,1 0,325 560.5 Z",
    "M556,560.5 A71,90.5 0,1 0,698 560.5 A71,90.5 0,1 0,556 560.5 Z",
    "M380,570 A25.5,49 0,1 0,431 570 A25.5,49 0,1 0,380 570 Z",
    "M611,570 A25.5,49 0,1 0,662 570 A25.5,49 0,1 0,611 570 Z",
    "M366,521.5 A13.5,13.5 0,1 0,393 521.5 A13.5,13.5 0,1 0,366 521.5 Z",
    "M597,521.5 A13.5,13.5 0,1 0,624 521.5 A13.5,13.5 0,1 0,597 521.5 Z",
    "M475,696 L548,696 L511.5,742 Z",
    "M800.5,91 C805.5,146 825.5,206 922,213.5 C825.5,221 805.5,281 800.5,336 " +
        "C795.5,281 775.5,221 679,213.5 C775.5,206 795.5,146 800.5,91 Z",
)

private val APPROVED_FOREGROUND_COLORS = listOf(
    "#1B1B1F",
    "#1B1B1F",
    "#1B1B1F",
    "#F472B6",
    "#F472B6",
    "#FDE047",
    "#FDE047",
    "#1B1B1F",
    "#1B1B1F",
    "#FFFFFF",
    "#FFFFFF",
    "#F472B6",
    "#FCD34D",
)

private val APPROVED_FOREGROUND_ALPHAS = listOf(
    "",
    "",
    "",
    "0.85",
    "0.85",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
)

private val APPROVED_MONOCHROME_PATHS = listOf(
    APPROVED_FOREGROUND_PATHS[0],
    APPROVED_FOREGROUND_PATHS[1],
    APPROVED_FOREGROUND_PATHS[2],
    APPROVED_FOREGROUND_PATHS[12],
)

private val OUTER_ARTWORK_POINTS = listOf(
    311.0 to 117.0,
    515.0 to 340.0,
    246.0 to 420.0,
    712.0 to 117.0,
    777.0 to 420.0,
    508.0 to 340.0,
    175.0 to 587.0,
    848.0 to 587.0,
    511.5 to 287.0,
    511.5 to 887.0,
    800.5 to 91.0,
    922.0 to 213.5,
    800.5 to 336.0,
    679.0 to 213.5,
)

private fun assertMaskSafeAndLegible(group: Element, vector: Element) {
    val scaleX = group.androidAttr("scaleX").toDouble()
    val scaleY = group.androidAttr("scaleY").toDouble()
    val pivotX = group.androidAttr("pivotX").toDouble()
    val pivotY = group.androidAttr("pivotY").toDouble()
    val translateX = group.androidAttr("translateX").toDouble()
    val translateY = group.androidAttr("translateY").toDouble()
    val viewportWidth = vector.androidAttr("viewportWidth").toDouble()
    val viewportHeight = vector.androidAttr("viewportHeight").toDouble()

    val radii = OUTER_ARTWORK_POINTS.map { (sourceX, sourceY) ->
        val x = ((sourceX - pivotX) * scaleX + pivotX + translateX) * 108.0 / viewportWidth
        val y = ((sourceY - pivotY) * scaleY + pivotY + translateY) * 108.0 / viewportHeight
        hypot(x - 54.0, y - 54.0)
    }
    (radii.max() <= 33.0) shouldBe true
    (radii.max() >= 30.0) shouldBe true
}

private fun xml(relativePath: String): Document =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(projectFile(relativePath))

private fun Document.singleGroup(): Element =
    getElementsByTagName("group").item(0) as Element

private fun Document.paths(): List<Element> =
    getElementsByTagName("path").let { nodes ->
        List(nodes.length) { index -> nodes.item(index) as Element }
    }

private fun Document.namedElement(tagName: String, name: String): Element =
    getElementsByTagName(tagName).let { nodes ->
        (0 until nodes.length)
            .map { index -> nodes.item(index) as Element }
            .first { element -> element.getAttribute("name") == name }
    }

private fun Element.childDrawable(tagName: String): String =
    getElementsByTagName(tagName).item(0).let { it as Element }.androidAttr("drawable")

private fun Element.androidAttr(name: String): String = getAttributeNS(ANDROID_NS, name)

private fun iconOverride(sourceSet: String, fileName: String): File =
    projectFile("app/src/$sourceSet/res/drawable/$fileName")

private fun projectFile(relativePath: String): File = File(projectRoot(), relativePath)

private fun projectRoot(): File =
    generateSequence(File(checkNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
        .take(8)
        .firstOrNull { File(it, "app/build.gradle.kts").isFile }
        ?: error("Unable to locate KittyEcho-Android project root")
