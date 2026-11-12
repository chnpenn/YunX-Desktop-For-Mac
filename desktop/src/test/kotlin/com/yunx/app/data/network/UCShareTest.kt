package com.yunx.app.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Intercepts every request before networking; never uses a real UC account. */
class UCShareTest {
    private val requests = mutableListOf<Request>()

    private fun api(vararg responses: Pair<Int, String>): UCApi {
        var index = 0
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requests.add(chain.request())
            val (http, body) = responses[index++]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(http).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return UCApi { client }
    }

    private suspend fun create(api: UCApi, fid: String = "file") =
        api.createShare(listOf(fid), "fixture", 1, "", 1, "test-cookie", "test-operation")

    @Test fun `file and folder preserve request and finish through share info`() = runBlocking {
        for (fid in listOf("file", "folder")) {
            requests.clear()
            val api = api(200 to """{"status":200,"data":{"task_id":"task"}}""",
                200 to """{"status":200,"data":{"status":2,"share_id":"internal"}}""",
                200 to """{"status":200,"data":{"share_url":"https://drive.uc.cn/s/public","pwd_id":"public"}}""")
            assertEquals("internal", create(api, fid))
            assertEquals("public", api.getShareInfo("internal", "test-cookie")?.pwdId)
            val buffer = okio.Buffer()
            requests.first().body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals(fid, body.getJSONArray("fid_list").getString(0))
            assertEquals(1, body.getInt("public_search"))
            assertEquals("/1/clouddrive/share/password", requests.last().url.encodedPath)
        }
    }

    @Test fun `pending state with timestamp and early id still waits and increments retry index`() = runBlocking {
        val api = api(200 to """{"status":200,"data":{"task_id":"task"}}""",
            200 to """{"status":200,"data":{"status":1,"finished_at":123,"share_id":"early"}}""",
            200 to """{"status":200,"data":{"status":2,"share_id":"ready"}}""")
        assertEquals("ready", create(api))
        assertEquals(listOf("0", "1"), requests.drop(1).map { it.url.queryParameter("retry_index") })
    }

    @Test fun `poll business rejection surfaces original message and code without retry`() = runBlocking {
        val api = api(200 to """{"status":200,"data":{"task_id":"task"}}""",
            200 to """{"status":400,"code":41013,"message":"file rejected"}""")
        val error = runCatching { create(api) }.exceptionOrNull() as QuarkApiException
        assertEquals("file rejected", error.message)
        assertEquals(41013, error.code)
        assertEquals(2, requests.size)
    }

    @Test fun `terminal failure is not accepted even with finished timestamp and id`() = runBlocking {
        val api = api(200 to """{"status":200,"data":{"task_id":"task"}}""",
            200 to """{"status":200,"data":{"status":3,"finished_at":123,"share_id":"bad","message":"blocked"}}""")
        assertEquals("blocked", runCatching { create(api) }.exceptionOrNull()?.message)
        assertEquals(2, requests.size)
    }

    @Test fun `HTTP and malformed poll responses are not swallowed`() = runBlocking {
        for ((http, payload, expected) in listOf(Triple(403, "denied", "HTTP 403"),
            Triple(200, "not json", "解析失败"))) {
            requests.clear()
            val api = api(200 to """{"status":200,"data":{"task_id":"task"}}""", http to payload)
            assertTrue(runCatching { create(api) }.exceptionOrNull()!!.message!!.contains(expected))
            assertEquals(2, requests.size)
        }
    }

    @Test fun `completed task missing id fails without retry`() = runBlocking {
        val api = api(200 to """{"status":200,"data":{"task_id":"task"}}""",
            200 to """{"status":200,"data":{"status":2}}""")
        assertTrue(runCatching { create(api) }.exceptionOrNull()!!.message!!.contains("缺少 share_id"))
        assertEquals(2, requests.size)
    }

    @Test fun `missing task and empty share URL fail explicitly`() = runBlocking {
        assertTrue(runCatching { create(api(200 to """{"status":200,"data":{}}""")) }
            .exceptionOrNull()!!.message!!.contains("task_id"))
        assertTrue(runCatching { api(200 to """{"status":200,"data":{}}""")
            .getShareInfo("internal", "test-cookie") }.exceptionOrNull()!!.message!!.contains("share_url"))
    }

    @Test fun `password share keeps protected request fields`() = runBlocking {
        val api = api(200 to """{"status":200,"data":{"task_id":"task"}}""",
            200 to """{"status":200,"data":{"status":2,"share_id":"ready"}}""")
        assertEquals("ready", api.createShare(listOf("file"), "fixture", 2, "1234", 3, "test-cookie"))
        val buffer = okio.Buffer()
        requests.first().body!!.writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        assertEquals(2, body.getInt("url_type"))
        assertEquals(3, body.getInt("expired_type"))
        assertEquals(0, body.getInt("public_search"))
        assertEquals("1234", body.getString("passcode"))
    }

    @Test fun `diagnostics redact URLs credentials and server identifiers`() {
        val safe = UCShareDiagnostics.safe("""Cookie=secret __puus=credential https://drive.uc.cn/s/capability "passcode":"1234", task_id=server-task share_id=server-share""")
        for (secret in listOf("secret", "credential", "capability", "1234", "server-task", "server-share")) {
            assertFalse("leaked $secret", safe.contains(secret))
        }
    }
}
