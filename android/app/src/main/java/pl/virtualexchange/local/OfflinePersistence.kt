package pl.virtualexchange.local

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Atomowy zapis snapshotu rynku offline. */
object OfflinePersistence {
    private const val FILE_NAME = "virtualexchange-offline-v1.json"

    @Synchronized
    fun load(context: Context): JSONObject? {
        val path = File(context.filesDir, FILE_NAME)
        val backupPath = File(context.filesDir, "$FILE_NAME.bak")
        if (!path.exists() && !backupPath.exists()) return null

        val file = AtomicFile(path)
        return try {
            file.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                val raw = reader.readText()
                require(raw.isNotBlank()) { "Zapis offline jest pusty" }
                JSONObject(raw)
            }
        } catch (error: Exception) {
            // Nie usuwaj pliku: może zawierać jedyną kopię portfeli i historii użytkownika.
            throw IllegalStateException("Zapis offline istnieje, ale nie można go odczytać. Plik nie został usunięty.", error)
        }
    }

    @Synchronized
    fun save(context: Context, snapshot: JSONObject) {
        val file = AtomicFile(File(context.filesDir, FILE_NAME))
        var stream: java.io.FileOutputStream? = null

        try {
            val output = file.startWrite()
            stream = output
            output.write(snapshot.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            if (stream != null) file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun clear(context: Context) {
        AtomicFile(File(context.filesDir, FILE_NAME)).delete()
    }
}
