package pl.virtualexchange.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object Api {
    // Emulator: 10.0.2.2. Telefon: wpisz IPv4 komputera w lokalnej sieci Wi-Fi.
    const val BASE_URL = "http://10.0.2.2:8000/"
    var apiKey: String = "demo-token"
    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun assets(): JSONArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(BASE_URL + "assets").get().build()
        client.newCall(req).execute().use { JSONArray(it.body?.string() ?: "[]") }
    }

    suspend fun me(): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(BASE_URL + "me").header("X-API-Key", apiKey).get().build()
        client.newCall(req).execute().use { JSONObject(it.body?.string() ?: "{}") }
    }

    suspend fun order(symbol: String, side: String, type: String, quantity: String, price: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("symbol", symbol).put("side", side)
                .put("order_type", type).put("quantity", quantity)
            if (price != null) body.put("price", price)
            val req = Request.Builder().url(BASE_URL + "orders").header("X-API-Key", apiKey)
                .post(body.toString().toRequestBody(jsonType)).build()
            client.newCall(req).execute().use { JSONObject(it.body?.string() ?: "{}") }
        }
}
