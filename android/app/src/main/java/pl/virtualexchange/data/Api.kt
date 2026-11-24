
package pl.virtualexchange.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object Api {
    const val BASE_URL = "http://10.0.2.2:8000/"
    var apiKey = "demo-token"
    private val client = OkHttpClient()
    private val type = "application/json; charset=utf-8".toMediaType()

    suspend fun getArray(path:String,key:Boolean=false) = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(BASE_URL+path)
        if(key) b.header("X-API-Key",apiKey)
        client.newCall(b.get().build()).execute().use {
            val body=it.body?.string().orEmpty()
            if(!it.isSuccessful) error(body)
            JSONArray(body)
        }
    }

    suspend fun getObject(path:String) = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(BASE_URL+path).build()).execute().use {
            val body=it.body?.string().orEmpty()
            if(!it.isSuccessful) error(body)
            JSONObject(body)
        }
    }

    suspend fun post(path:String,data:JSONObject,key:Boolean=true) = withContext(Dispatchers.IO) {
        val b=Request.Builder().url(BASE_URL+path)
            .post(data.toString().toRequestBody(type))
        if(key) b.header("X-API-Key",apiKey)
        client.newCall(b.build()).execute().use {
            val body=it.body?.string().orEmpty()
            if(!it.isSuccessful) error(body)
            JSONObject(body)
        }
    }
}
