package com.yunx.app.ui.viewmodel

import com.yunx.app.data.db.GuangYaAccountDao
import com.yunx.app.data.db.GuangYaAccountEntity
import com.yunx.app.data.network.GuangYaApi
import com.yunx.app.data.repository.GuangYaAccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GuangYaLoginResultTest {
    private class Dao : GuangYaAccountDao {
        val account = MutableStateFlow<GuangYaAccountEntity?>(null)
        var failSave = false
        override fun observeAccount() = account
        override fun observeAccounts() = account.map { listOfNotNull(it) }
        override suspend fun getAccount() = account.value
        override suspend fun getAccounts() = listOfNotNull(account.value)
        override suspend fun upsert(account: GuangYaAccountEntity) {
            check(!failSave) { "账号保存失败" }
            this.account.value = account
        }
        override suspend fun setPrimary(id: String) = error("unused")
        override suspend fun updateRemark(id: String, remark: String) = error("unused")
        override suspend fun deleteById(id: String) = error("unused")
        override suspend fun clear() { account.value = null }
    }

    private fun model(dao: Dao, rejectLogin: Boolean = false): GuangYaAccountViewModel {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val body = when {
                path.endsWith("/verify") -> """{"verification_token":"test-verification"}"""
                path.endsWith("/captcha/init") -> "{}"
                path.endsWith("/signin") && rejectLogin -> """{"error":"invalid_grant","error_description":"测试登录失败"}"""
                path.endsWith("/signin") -> """{"access_token":"test-access","refresh_token":"test-refresh"}"""
                path.endsWith("/me") -> """{"sub":"test-user","name":"测试账号"}"""
                else -> error("Unexpected request: $path")
            }
            // No proceed(): tests never contact the real GuangYa service.
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("offline").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return GuangYaAccountViewModel(GuangYaAccountRepository(dao, GuangYaApi { client }))
    }

    @Test fun smsVerificationFailureDoesNotReportSuccess() = runBlocking {
        val dao = Dao()
        val result = model(dao).smsLogin("13000000000", "test-id", "12", true)
        assertFalse(result.first)
        assertTrue(result.second.contains("验证码"))
        assertNull(dao.account.value)
    }

    @Test fun passwordApiFailureDoesNotReportSuccess() = runBlocking {
        val dao = Dao()
        val result = model(dao, rejectLogin = true).login("13000000000", "test-password")
        assertFalse(result.first)
        assertNotEquals("登录成功", result.second)
        assertNull(dao.account.value)
    }

    @Test fun smsSaveFailureDoesNotReportSuccess() = runBlocking {
        val dao = Dao().apply { failSave = true }
        assertEquals(false to "账号保存失败", model(dao).smsLogin("13000000000", "test-id", "123456", true))
        assertNull(dao.account.value)
    }

    @Test fun smsSuccessRequiresSavedAccount() = runBlocking {
        val dao = Dao()
        assertEquals(true to "登录成功", model(dao).smsLogin("13000000000", "test-id", "123456", true))
        assertEquals("test-access", dao.account.value?.accessToken)
    }
}
