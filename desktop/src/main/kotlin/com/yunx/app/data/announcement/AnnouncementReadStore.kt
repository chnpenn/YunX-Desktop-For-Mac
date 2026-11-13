package com.yunx.app.data.announcement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.prefs.Preferences

/** 已读公告版本的本地记录；打开详情或关闭启动提示时保存，最多保留 500 项。 */
class AnnouncementReadStore {

    private val prefs: Preferences = Preferences.userRoot().node("yunx/announcement")

    private val lock = Any()

    /** 有序（最新在前）的已读 id，内存里的唯一真源 */
    private var ordered: List<String> = loadOrdered()

    private val _readIds = MutableStateFlow(ordered.toSet())

    /** 已读 id 集合（供 Compose 观察；写入后立即更新） */
    val readIds: StateFlow<Set<String>> = _readIds.asStateFlow()

    /** 标记一条公告已读；已读过则直接返回 */
    fun markRead(id: String) {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) {
            if (trimmed in _readIds.value) return
            ordered = (listOf(trimmed) + ordered).take(MAX_KEEP)
            persistLocked()
            _readIds.value = ordered.toSet()
        }
    }

    /**
     * 批量标记已读（「全部标为已读」）。
     * @return 本次真正新增的条数（0 表示这些公告本来就都读过了，调用方可据此决定要不要提示）
     */
    fun markAllRead(ids: Collection<String>): Int {
        val fresh = ids.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .filter { it !in _readIds.value }
        if (fresh.isEmpty()) return 0
        synchronized(lock) {
            // 锁内再取一次差集：上面那轮过滤是为了不改锁外可见状态，这里保证真正落盘的没有重复
            val stillFresh = fresh.filter { it !in _readIds.value }
            if (stillFresh.isEmpty()) return 0
            ordered = (stillFresh + ordered).distinct().take(MAX_KEEP)
            persistLocked()
            _readIds.value = ordered.toSet()
            return stillFresh.size
        }
    }

    private fun loadOrdered(): List<String> {
        val text = prefs.get(KEY_READ_IDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                if (arr.isNull(i)) continue
                val id = arr.optString(i).trim()
                if (id.isNotEmpty()) out.add(id)
            }
            out
        } catch (e: Exception) {
            // 记录损坏（手改注册表 / 写一半崩了）：当作没有已读记录，不要因为一个偏好项让应用起不来
            emptyList()
        }
    }

    private fun persistLocked() {
        prefs.put(KEY_READ_IDS, JSONArray(ordered).toString())
    }

    companion object {
        private const val KEY_READ_IDS = "read_ids"

        /** 最多保留的已读记录条数（见类注释：多出来的从最旧的开始丢） */
        private const val MAX_KEEP = 500
    }
}

/** 已确认的更高版本也覆盖旧版本，仓库回滚不会重新弹出旧公告。 */
internal fun AnnouncementApi.Announcement.isRead(readKeys: Set<String>): Boolean {
    val prefix = "git:$id@"
    return readKeys.any { key ->
        key.startsWith(prefix) && (key.removePrefix(prefix).toLongOrNull() ?: 0) >= version
    }
}
