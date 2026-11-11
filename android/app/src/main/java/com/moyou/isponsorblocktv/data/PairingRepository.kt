package com.moyou.isponsorblocktv.data

import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject

class PairingRepository @Inject constructor(private val transport: HttpTransport) {
    data class PairingResult(val screenId: String, val name: String)

    suspend fun pairWithCode(userCode: String): PairingResult {
        val code = userCode.filter { !it.isWhitespace() && it != '-' }
        require(code.length in 6..20 && code.all(Char::isDigit)) {
            "أدخل الرمز الرقمي الظاهر في إعدادات YouTube على التلفاز"
        }
        val response = transport.postForm(
            "https://www.youtube.com/api/lounge/pairing/get_screen",
            mapOf("pairing_code" to code),
        )
        if (response.code != 200) throw IOException("فشل الربط مع YouTube (HTTP ${response.code})")
        val screen = JSONObject(response.body).optJSONObject("screen")
            ?: throw IOException("الرمز غير صالح أو انتهت صلاحيته")
        val id = screen.optString("screenId")
        if (id.isBlank()) throw IOException("لم يُرجع YouTube معرّف التلفاز")
        return PairingResult(id, screen.optString("name", "YouTube TV"))
    }
}
