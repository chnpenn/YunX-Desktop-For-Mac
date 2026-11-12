package com.yunx.app.data.network

import com.yunx.app.util.Log
import com.yunx.app.util.LogRedactor
import org.json.JSONObject
import java.util.UUID

/** Always persisted by Log, including when optional diagnostic mode is off. */
internal object UCShareDiagnostics {
    fun newId(): String = UUID.randomUUID().toString()

    fun event(id: String, event: String, vararg fields: Pair<String, Any?>) {
        val json = JSONObject().put("operation", id).put("event", event)
        fields.forEach { (key, value) ->
            json.put(key, if (value is String) safe(value) else value ?: JSONObject.NULL)
        }
        Log.i("UCShare", json.toString())
    }

    fun failure(id: String, eventName: String, error: Exception) = event(id, eventName,
        "exception" to error.javaClass.simpleName, "message" to error.message,
        "code" to (error as? QuarkApiException)?.code)

    internal fun safe(value: String): String = LogRedactor.line(value)
        .replace(Regex("""(?i)["']?(passcode|pwd_id|share_id|task_id)["']?\s*[=:]\s*["']?[^\s,;"']+["']?"""), "$1=<redacted>")
        .replace('\n', ' ').replace('\r', ' ').take(600)
}
