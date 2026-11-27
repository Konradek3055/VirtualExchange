package pl.virtualexchange.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.virtualexchange.local.LocalApi
import pl.virtualexchange.local.OfflineMarket
import pl.virtualexchange.local.OfflinePersistence
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiError(raw: String, val code: Int) : Exception(friendly(raw))

private fun friendly(raw: String): String = try {
    val d = JSONObject(raw).opt("detail")
    when (d) {
        is String -> d
        is JSONArray -> (0 until d.length()).joinToString("; ") { d.getJSONObject(it).optString("msg") }
        else -> raw
    }
} catch (e: Exception) {
    raw
}

object Api {
    // Emulator: 10.0.2.2 = komputer z backendem. Na telefonie zmień w zakładce Więcej -> Konto i serwer.
    var baseUrl = "http://10.0.2.2:8000/"
    var apiKey = "demo-token"

    /** true = tryb offline: lokalny silnik + boty na telefonie, bez serwera (domyślnie). */
    var offline = true
        private set

    private var appContext: Context? = null
    private var market: OfflineMarket? = null
    /** Blokuje nadpisanie nieodczytywalnego zapisu nową, pustą sesją. */
    private var offlinePersistenceBlocked = false
    private var botJob: Job? = null
    private val botScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var prefs: SharedPreferences? = null
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private var socket: WebSocket? = null

    /** Każda wiadomość z WebSocketa (zmiana rynku) emituje tu symbol aktywa. */
    val ticks = MutableSharedFlow<String>(extraBufferCapacity = 16)

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = context.getSharedPreferences("virtualexchange", Context.MODE_PRIVATE)
        prefs = p
        baseUrl = p.getString("baseUrl", baseUrl) ?: baseUrl
        apiKey = p.getString("apiKey", apiKey) ?: apiKey
        offline = p.getBoolean("offline", true)
        applyMode()
    }

    @Synchronized
    private fun localMarket(): OfflineMarket {
        var m = market
        if (m == null) {
            val context = appContext
            val saved = try {
                context?.let { OfflinePersistence.load(it) }
            } catch (error: Exception) {
                offlinePersistenceBlocked = true
                android.util.Log.e("VirtualExchange", "Nie można odczytać zapisu offline; oryginalny plik pozostaje zachowany.", error)
                null
            }

            m = try {
                if (offlinePersistenceBlocked || saved == null) OfflineMarket()
                else OfflineMarket(restoredState = saved)
            } catch (error: Exception) {
                // Nie czyścimy pliku i nie zapisujemy na niego nowej sesji. Użytkownik może odzyskać zapis.
                offlinePersistenceBlocked = true
                android.util.Log.e("VirtualExchange", "Nie można odtworzyć zapisu offline; oryginalny plik pozostaje zachowany.", error)
                OfflineMarket()
            }
            market = m
            if (!offlinePersistenceBlocked) persistOffline(m)
        }
        return m
    }

    @Synchronized
    private fun persistOffline(m: OfflineMarket) {
        if (offlinePersistenceBlocked) return
        val context = appContext ?: return

        try {
            OfflinePersistence.save(context, m.snapshot())
        } catch (error: Exception) {
            android.util.Log.e(
                "VirtualExchange",
                "Nie udało się zapisać rynku offline",
                error,
            )
        }
    }

    fun setOffline(value: Boolean) {
        offline = value
        prefs?.edit()?.putBoolean("offline", value)?.apply()
        applyMode()
    }

    /** Nowa symulacja od zera (nowe saldo gracza, nowe boty i historia). */
    fun resetOffline() {
        botJob?.cancel()

        synchronized(this) {
            market = null
            offlinePersistenceBlocked = false
            appContext?.let { OfflinePersistence.clear(it) }
        }

        applyMode()
    }

    private fun applyMode() {
        botJob?.cancel()
        botJob = null
        if (offline) {
            disconnect()
            botJob = botScope.launch {
                val m = localMarket()
                var stepCount = 0

                while (isActive) {
                    delay(1500)
                    try {
                        m.step()
                        stepCount++
                        // Zapis botów co 5 udanych kroków.
                        if (stepCount % 5 == 0) persistOffline(m)
                    } catch (error: Exception) {
                        // Błąd pojedynczej decyzji bota nie może ubić całej aplikacji.
                        android.util.Log.e("VirtualExchange", "Błąd kroku rynku offline; symulacja spróbuje ponownie.", error)
                    }
                    ticks.tryEmit("*")
                }
            }
        }
    }

    fun pauseBots() {
        botJob?.cancel()
        botJob = null
    }

    fun save(url: String, key: String) {
        var u = url.trim()
        if (!u.startsWith("http")) u = "http://$u"
        if (!u.endsWith("/")) u += "/"
        baseUrl = u
        apiKey = key.trim()
        prefs?.edit()?.putString("baseUrl", baseUrl)?.putString("apiKey", apiKey)?.apply()
    }

    private suspend fun call(method: String, path: String, data: JSONObject?, auth: Boolean): String =
        withContext(Dispatchers.IO) {
            if (offline) {
                val local = localMarket()
                val response = LocalApi.handle(local, method, path, data)

                // Zlecenia, anulowanie i tworzenie aktywów zapisujemy natychmiast.
                if (method != "GET") persistOffline(local)

                return@withContext response
            }
            val b = Request.Builder().url(baseUrl + path)
            if (auth) b.header("X-API-Key", apiKey)
            when (method) {
                "GET" -> b.get()
                "DELETE" -> b.delete()
                else -> b.post((data ?: JSONObject()).toString().toRequestBody(json))
            }
            client.newCall(b.build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiError(body, r.code)
                body
            }
        }

    suspend fun getArray(path: String, auth: Boolean = false) = JSONArray(call("GET", path, null, auth))
    suspend fun getObject(path: String, auth: Boolean = false) = JSONObject(call("GET", path, null, auth))
    suspend fun post(path: String, data: JSONObject, auth: Boolean = true) =
        JSONObject(call("POST", path, data, auth))

    suspend fun delete(path: String) = JSONObject(call("DELETE", path, null, true))

    /** Rejestracja nowego konta (10 000 wirtualnych USD). Zwraca JSON z api_key. */
    suspend fun register(username: String) =
        post("accounts", JSONObject().put("username", username.trim()), auth = false)

    fun connect(symbol: String) {
        disconnect()
        if (offline) return
        try {
            val url = baseUrl.replaceFirst("http", "ws") + "ws/" + symbol
            socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    ticks.tryEmit(symbol)
                }
            })
        } catch (e: Exception) {
            socket = null   // zły adres - działa odświeżanie cykliczne
        }
    }

    fun disconnect() {
        socket?.close(1000, null)
        socket = null
    }
}
