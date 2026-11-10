package com.yunx.app.ui.viewmodel

import com.yunx.app.data.db.MultiAccountEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * null 表示尚未读取账号；空列表表示已读取、确实没有账号。
 * 账号面板会在列表为空时自动关闭，不能把加载中的占位值当成退出了最后一个账号。
 * 停订后清除缓存，重开面板时等待当前列表，避免旧的空列表再次触发关闭。
 */
internal fun Flow<List<MultiAccountEntity>>.shareAccountList(
    scope: CoroutineScope
): StateFlow<List<MultiAccountEntity>?> = stateIn(
    scope = scope,
    started = SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0),
    initialValue = null
)
