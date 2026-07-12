package com.wordtaker.keyboard.wordtaker.backend

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 云词库联想 [BackendClient.dictSuggest] 单元级自测：打 MockWebServer，不碰真实后端。
 *
 * 覆盖契约形状（路径/方法/请求体/鉴权头）与「静默降级为空列表」的全部失败分支
 * （超时 / 非 2xx / success=false / 畸形 JSON），保证任何异常都不会向上抛、只返回空。
 */
class DictSuggestTest : FunSpec({

    lateinit var server: MockWebServer
    var token: String? = null

    fun client(): BackendClient = BackendClient(
        deviceId = DICT_DEVICE_ID,
        tokenProvider = { token },
        baseUrl = server.url("/aiapi").toString(),
    )

    beforeTest {
        server = MockWebServer()
        server.start()
        token = null
    }

    afterTest {
        server.shutdown()
    }

    test("200 + valid envelope parses candidates with text/score/source") {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"candidates":[
                   {"text":"你好","score":0.98,"source":"cloud"},
                   {"text":"你号","score":0.42,"source":"cloud"}]}}""",
            ),
        )

        val out = client().dictSuggest("nihao")

        out shouldHaveSize 2
        out[0].text shouldBe "你好"
        out[0].score shouldBe 0.98
        out[0].source shouldBe "cloud"
        out[1].text shouldBe "你号"
        out[1].source shouldBe "cloud"
    }

    test("skips candidate entries with blank text; keeps null source") {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"candidates":[
                   {"text":"","score":0.9},
                   {"text":"喵","score":0.5}]}}""",
            ),
        )

        val out = client().dictSuggest("miao")

        out shouldHaveSize 1
        out[0].text shouldBe "喵"
        out[0].score shouldBe 0.5
        out[0].source.shouldBeNull()
    }

    test("request carries contract path, method, headers and body fields") {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"candidates":[]}}"""))
        token = "jwt-dict-1"

        client().dictSuggest("nihao", limit = 5, prefix = true, context = "今天")

        val recorded = server.takeRequest()
        recorded.path shouldBe "/aiapi/dict/suggest"
        recorded.method shouldBe "POST"
        recorded.getHeader("x-device-id") shouldBe DICT_DEVICE_ID
        recorded.getHeader("x-platform") shouldBe "android"
        recorded.getHeader("Authorization") shouldBe "Bearer jwt-dict-1"

        val body = JSONObject(recorded.body.readUtf8())
        body.getString("pinyin") shouldBe "nihao"
        body.getInt("limit") shouldBe 5
        body.getBoolean("prefix") shouldBe true
        body.getString("context") shouldBe "今天"
    }

    test("blank context is omitted from the request body") {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"candidates":[]}}"""))

        client().dictSuggest("nihao")

        val body = JSONObject(server.takeRequest().body.readUtf8())
        body.has("context") shouldBe false
        body.getBoolean("prefix") shouldBe false
    }

    test("timeout is swallowed and returns empty list") {
        // callTimeout for dict/suggest is 500ms; delay headers past it to force InterruptedIOException.
        server.enqueue(
            MockResponse()
                .setBody("""{"success":true,"data":{"candidates":[{"text":"迟","score":1}]}}""")
                .setHeadersDelay(1500, TimeUnit.MILLISECONDS),
        )

        client().dictSuggest("chi").shouldBeEmpty()
    }

    test("non-2xx (500) is swallowed and returns empty list") {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setBody("""{"code":"INTERNAL","message":"boom"}"""),
        )

        client().dictSuggest("wu").shouldBeEmpty()
    }

    test("HTTP 200 but success=false returns empty even when data.candidates is present") {
        // 真·success=false：故意让 data 带候选，验证客户端尊重 envelope 的 success 而非只看 data。
        server.enqueue(
            MockResponse().setBody(
                """{"success":false,"error":"NOPE","data":{"candidates":[{"text":"混","score":0.9}]}}""",
            ),
        )

        client().dictSuggest("mei").shouldBeEmpty()
    }

    test("network failure (connection dropped) is swallowed and returns empty list") {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        client().dictSuggest("wang").shouldBeEmpty()
    }

    test("malformed JSON body returns empty list") {
        server.enqueue(MockResponse().setBody("""{not-json"""))

        client().dictSuggest("huai").shouldBeEmpty()
    }

    test("candidates missing / not an array returns empty list") {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{}}"""))

        client().dictSuggest("kong").shouldBeEmpty()
    }
})

private const val DICT_DEVICE_ID = "0123456789abcdef0123456789abcdef"
