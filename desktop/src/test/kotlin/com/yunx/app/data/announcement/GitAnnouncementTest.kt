package com.yunx.app.data.announcement

import com.yunx.app.ui.viewmodel.pickPopupCandidate
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class GitAnnouncementTest {
    private fun item(id: String = "notice", version: Number = 1, published: String = "2020-01-01T00:00:00Z") =
        JSONObject().put("id", id).put("version", version).put("title", "公告")
            .put("content", "## 正文").put("publishAt", published)

    private fun feed(vararg items: JSONObject) = JSONObject().put("schemaVersion", 1)
        .put("announcements", JSONArray(items.toList())).toString()

    @Test fun repositoryFileIsValid() {
        val items = AnnouncementApi.parseFeed(File("../announcements/announcements.json").readText())
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.version > 0 && !it.content.isNullOrBlank() })
    }

    @Test fun acknowledgedVersionSurvivesRestartAndHigherVersionPromptsAgain() {
        val id = UUID.randomUUID().toString()
        val v1 = AnnouncementApi.parseFeed(feed(item(id, 1))).single()
        val store = AnnouncementReadStore()
        assertNotNull(pickPopupCandidate(listOf(v1), store.readIds.value))
        store.markRead(v1.readKey)
        val restarted = AnnouncementReadStore()
        assertNull(pickPopupCandidate(listOf(v1), restarted.readIds.value))
        val v2 = AnnouncementApi.parseFeed(feed(item(id, 2))).single()
        assertEquals(v2, pickPopupCandidate(listOf(v2), restarted.readIds.value))
        restarted.markRead(v2.readKey)
        assertTrue(v1.isRead(restarted.readIds.value))
        assertTrue(v2.isRead(restarted.readIds.value))
    }

    @Test fun futureAnnouncementsDoNotDisplayAndEmptyFeedIsAllowed() {
        assertTrue(AnnouncementApi.parseFeed(feed(item(published = "2099-01-01T00:00:00Z")), now = 0).isEmpty())
        assertTrue(AnnouncementApi.parseFeed(feed()).isEmpty())
    }

    @Test fun duplicateIdsAndNonIntegerVersionsAreRejected() {
        for (invalid in listOf(feed(item(), item()), feed(item(version = 1.5)), feed(item(version = 0)))) {
            try { AnnouncementApi.parseFeed(invalid); fail("Invalid feed accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun listPaginationAndDetailsShareOneSnapshotAndRefreshGetsNewContent() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(feed(item("first"), item("second"))))
            val source = AnnouncementApi.GitSource({ OkHttpClient() }, server.url("/announcements.json").toString())
            val first = (source.fetchPage(1, 1) as AnnouncementApi.Result.Success).data
            assertTrue(first.hasMore)
            assertEquals("first", first.list.single().id)
            val second = (source.fetchPage(2, 1) as AnnouncementApi.Result.Success).data
            assertFalse(second.hasMore)
            assertEquals("second", second.list.single().id)
            assertEquals("## 正文", (source.fetchDetail("first") as AnnouncementApi.Result.Success).data.content)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody(feed(item("first", 2).put("content", "修改后的正文"))))
            val updated = (source.fetchDetail("first", refresh = true) as AnnouncementApi.Result.Success).data
            assertEquals(2L, updated.version)
            assertEquals("修改后的正文", updated.content)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun failedOrMalformedResponseCannotReplaceLastSuccessfulSnapshot() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val source = AnnouncementApi.GitSource({ OkHttpClient() }, server.url("/feed.json").toString())
            server.enqueue(MockResponse().setBody(feed(item())))
            assertTrue(source.fetchPage() is AnnouncementApi.Result.Success)
            server.enqueue(MockResponse().setResponseCode(502).setBody(feed(item(version = 2))))
            assertTrue(source.fetchPage() is AnnouncementApi.Result.Failure)
            server.enqueue(MockResponse().setBody("<html>bad gateway</html>"))
            assertTrue(source.fetchPage() is AnnouncementApi.Result.Failure)
            val retained = (source.fetchDetail("notice") as AnnouncementApi.Result.Success).data
            assertEquals(1L, retained.version)
        }
    }
}
