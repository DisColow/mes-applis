package fr.mesapplis.relance

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** L'accès à Google Tasks doit être (ré)autorisé depuis l'appli. */
class NeedsAuthException(msg: String = "Connexion à Google Tasks nécessaire") : Exception(msg)

data class GTask(
    val listId: String,
    val listTitle: String,
    val id: String,
    val title: String,
    val notes: String,
    val due: LocalDate?,
)

object GoogleAuth {
    const val SCOPE = "https://www.googleapis.com/auth/tasks"

    fun request(account: String?): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .apply { if (account != null) setAccount(Account(account, "com.google")) }
            .build()

    /** Jeton d'accès, sans interface. À appeler hors du fil principal. */
    fun tokenBlocking(ctx: Context): String {
        val prefs = Prefs(ctx)
        if (!prefs.tasksConnected) throw NeedsAuthException()
        val result = try {
            Tasks.await(
                Identity.getAuthorizationClient(ctx).authorize(request(prefs.account)),
                30, TimeUnit.SECONDS,
            )
        } catch (e: Exception) {
            throw IOException("Autorisation Google impossible : ${e.cause?.message ?: e.message}", e)
        }
        if (result.hasResolution()) throw NeedsAuthException()
        return result.accessToken ?: throw NeedsAuthException()
    }

    fun api(ctx: Context) = TasksApi(tokenBlocking(ctx))
}

/** Petit client REST pour l'API Google Tasks v1. */
class TasksApi(private val token: String) {
    private val base = "https://tasks.googleapis.com/tasks/v1"

    fun lists(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var page: String? = null
        do {
            val o = call("GET", "/users/@me/lists?maxResults=100" + (page?.let { "&pageToken=${enc(it)}" } ?: ""))
            val items = o.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val l = items!!.getJSONObject(i)
                out += l.getString("id") to l.optString("title")
            }
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    fun openTasks(listId: String, listTitle: String): List<GTask> {
        val out = mutableListOf<GTask>()
        var page: String? = null
        do {
            val o = call("GET", "/lists/${enc(listId)}/tasks?showCompleted=false&showHidden=false&maxResults=100" +
                (page?.let { "&pageToken=${enc(it)}" } ?: ""))
            val items = o.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val t = items!!.getJSONObject(i)
                val title = t.optString("title").trim()
                if (title.isEmpty() || t.optString("status") == "completed") continue
                out += GTask(listId, listTitle, t.getString("id"), title,
                    t.optString("notes").trim(), Logic.parseDue(t.optString("due")))
            }
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    fun allOpenTasks(): List<GTask> = lists().flatMap { (id, title) -> openTasks(id, title) }

    fun complete(listId: String, taskId: String) {
        call("PATCH", "/lists/${enc(listId)}/tasks/${enc(taskId)}", JSONObject().put("status", "completed"))
    }

    fun setDue(listId: String, taskId: String, due: LocalDate) {
        call("PATCH", "/lists/${enc(listId)}/tasks/${enc(taskId)}", JSONObject().put("due", Logic.formatDue(due)))
    }

    fun create(title: String, notes: String, due: LocalDate?, listId: String = "@default") {
        val body = JSONObject().put("title", title).put("notes", notes)
        if (due != null) body.put("due", Logic.formatDue(due))
        call("POST", "/lists/${enc(listId)}/tasks", body)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection ne connaît pas PATCH : les API Google acceptent cet en-tête.
            if (method == "PATCH") {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw NeedsAuthException()
            if (code !in 200..299) throw IOException("Google Tasks a répondu $code : ${text.take(300)}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
