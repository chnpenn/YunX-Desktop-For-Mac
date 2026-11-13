package com.yunx.app.data.announcement

import com.yunx.app.data.network.HttpClients
import com.yunx.app.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Git 仓库公告：列表和正文来自同一份只读 JSON，不依赖公告服务器。 */
object AnnouncementApi {
    const val FEED_URL = "https://raw.githubusercontent.com/chnpenn/YunX-Desktop-For-Mac/mac-port/announcements/announcements.json"
    const val PAGE_SIZE = 100

    /** 发布者信息（供现有公告界面展示） */
    data class Publisher(val name: String, val avatarUrl: String?)

    /**
     * 公告展示模型。
     * [content] 与列表一同从 Git 文件读取；[coverImage] / [images] / [publisher.avatarUrl] 都是**完整直链**，可直接加载。
     */
    data class Announcement(
        val id: String,
        val title: String,
        val summary: String,
        val content: String?,
        val coverImage: String?,
        val images: List<String>,
        val publishAt: String?,
        val author: String,
        val viewCount: Long,
        val publisher: Publisher,
        val isPinned: Boolean,
        val pinnedAt: String?,
        val pinExpireAt: String?,
        val sortOrder: Int,
        val createdAt: String,
        val updatedAt: String,
        val version: Long = 1,
    ) {
        /** 同一公告正文更新并递增版本后，重新计为未读。 */
        val readKey: String get() = "git:$id@$version"

        /** 生效时间（毫秒）：`publishAt` 为空表示「立即发布」，退回 createdAt；两者都解析不出来才是 0 */
        val effectiveMillis: Long get() = parseIsoMillis(publishAt) ?: parseIsoMillis(createdAt) ?: 0L
    }

    /** 本地分页结果：`hasMore` 等价于 `page * pageSize < total` */
    data class Page(
        val total: Int,
        val page: Int,
        val pageSize: Int,
        val totalPages: Int,
        val hasMore: Boolean,
        val list: List<Announcement>,
    )

    /** 请求结果：失败带上可直接展示的文件、HTTP 或网络错误原因 */
    sealed interface Result<out T> {
        data class Success<T>(val data: T) : Result<T>
        data class Failure(val message: String) : Result<Nothing>
    }

    private val source = GitSource({ HttpClients.apiClient() }, FEED_URL)

    suspend fun fetchPage(page: Int = 1, pageSize: Int = PAGE_SIZE): Result<Page> =
        source.fetchPage(page, pageSize)

    suspend fun fetchDetail(id: String, refresh: Boolean = false): Result<Announcement> =
        source.fetchDetail(id, refresh)

    /** 请求源可在测试中绑定 MockWebServer；生产只读取固定的公开仓库文件。 */
    internal class GitSource(private val client: () -> OkHttpClient, private val url: String) {
        @Volatile private var snapshot: List<Announcement>? = null

        private suspend fun load(): Result<List<Announcement>> = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "YunX-Desktop")
                    .header("Cache-Control", "no-cache").build()
                client().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext Result.Failure("公告文件请求失败（HTTP ${response.code}）")
                    val items = parseFeed(response.body?.string().orEmpty())
                    snapshot = items
                    Result.Success(items)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("YunX-Announce", "Git 公告读取失败：${e.message}", e)
                Result.Failure(e.message ?: "公告文件读取失败")
            }
        }

        suspend fun fetchPage(page: Int = 1, pageSize: Int = PAGE_SIZE): Result<Page> {
            if (page < 1 || pageSize !in 1..PAGE_SIZE) return Result.Failure("公告分页参数无效")
            val result = if (page == 1 || snapshot == null) load() else Result.Success(snapshot!!)
            return when (result) {
                is Result.Failure -> result
                is Result.Success -> {
                    val all = result.data
                    val start = ((page.toLong() - 1) * pageSize).coerceAtMost(all.size.toLong()).toInt()
                    val items = all.drop(start).take(pageSize)
                    Result.Success(Page(all.size, page, pageSize, (all.size + pageSize - 1) / pageSize,
                        start + items.size < all.size, items))
                }
            }
        }

        suspend fun fetchDetail(id: String, refresh: Boolean = false): Result<Announcement> {
            val result = if (refresh || snapshot == null) load() else Result.Success(snapshot!!)
            return when (result) {
                is Result.Failure -> result
                is Result.Success -> result.data.firstOrNull { it.id == id }?.let { Result.Success(it) }
                    ?: Result.Failure("公告不存在或已移除")
            }
        }
    }

    internal fun parseFeed(body: String, now: Long = System.currentTimeMillis()): List<Announcement> {
        val root = JSONObject(body)
        require(root.getInt("schemaVersion") == 1) { "不支持的公告文件格式版本" }
        val array = root.getJSONArray("announcements")
        val ids = mutableSetOf<String>()
        val items = (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            val id = json.getString("id").trim()
            require(id.isNotEmpty() && ids.add(id)) { "公告 ID 为空或重复：$id" }
            val version = (json.get("version") as? Number)?.toString()?.toLongOrNull()
            require(version != null && version > 0) { "公告版本号必须为正整数" }
            val title = json.getString("title").trim()
            val content = json.getString("content").trim()
            require(title.isNotEmpty() && content.isNotEmpty()) { "公告标题和正文不能为空" }
            val published = json.stringOrNull("publishAt")
            require(published == null || parseIsoMillis(published) != null) { "公告发布时间格式无效" }
            val author = json.stringOrNull("author") ?: "云析 macOS"
            Announcement(
                id = id, title = title, summary = json.stringOrNull("summary").orEmpty(), content = content,
                coverImage = json.stringOrNull("coverImage"), images = emptyList(), publishAt = published,
                author = author, viewCount = 0, publisher = Publisher(author, null),
                isPinned = json.optBoolean("isPinned"), pinnedAt = null, pinExpireAt = null,
                sortOrder = json.optInt("sortOrder"), createdAt = published.orEmpty(),
                updatedAt = json.stringOrNull("updatedAt") ?: published.orEmpty(), version = version
            )
        }
        return items.filter { it.effectiveMillis <= now }
            .sortedWith(compareByDescending<Announcement> { it.isPinned }
                .thenByDescending { it.sortOrder }.thenByDescending { it.effectiveMillis })
    }
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
