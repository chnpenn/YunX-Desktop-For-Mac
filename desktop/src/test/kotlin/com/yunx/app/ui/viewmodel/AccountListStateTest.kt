package com.yunx.app.ui.viewmodel

import com.yunx.app.data.db.MultiAccountEntity
import com.yunx.app.data.db.QuarkAccountEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountListStateTest {
    private val account = QuarkAccountEntity(nickname = "Test account", cookie = "test-only")

    @Test
    fun firstSubscriptionDoesNotReportEmptyAccountsWhileLoading() = runTest {
        val source = flow<List<MultiAccountEntity>> {
            delay(250)
            emit(listOf(account))
            awaitCancellation()
        }
        val state = source.shareAccountList(backgroundScope)
        val received = mutableListOf<List<MultiAccountEntity>?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.collect { received.add(it) }
        }
        runCurrent()

        assertNull(state.value)
        advanceTimeBy(249)
        runCurrent()
        assertEquals(listOf<List<MultiAccountEntity>?>(null), received)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(null, listOf(account)), received)
    }

    @Test
    fun removingLastAccountStillReportsAnActualEmptyList() = runTest {
        val source = MutableStateFlow<List<MultiAccountEntity>>(listOf(account))
        val state = source.shareAccountList(backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
        runCurrent()
        assertEquals(listOf(account), state.value)

        source.value = emptyList()
        runCurrent()
        assertEquals(emptyList<MultiAccountEntity>(), state.value)
    }

    @Test
    fun reopeningAfterUnsubscribeDoesNotExposeAStaleEmptyList() = runTest {
        val source = MutableStateFlow<List<MultiAccountEntity>>(emptyList())
        val state = source.shareAccountList(backgroundScope)
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.collect {}
        }
        runCurrent()
        // A genuine empty database must remain distinguishable from loading.
        assertEquals(emptyList<MultiAccountEntity>(), state.value)

        subscription.cancel()
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertNull(state.value)

        // Account added while the panel is closed and its upstream is stopped.
        source.value = listOf(account)
        val received = mutableListOf<List<MultiAccountEntity>?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.collect { received.add(it) }
        }
        runCurrent()
        assertEquals(listOf(null, listOf(account)), received)
    }
}
