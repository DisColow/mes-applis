package fr.mesapplis.relance

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Un événement terminé sur lequel on doit (re)poser la question « c'est fait ? ». */
data class Question(
    val key: String,
    val title: String,
    val description: String,
    val begin: Long,
    val end: Long,
    val askAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("title", title).put("description", description)
        .put("begin", begin).put("end", end).put("askAt", askAt)

    companion object {
        fun fromJson(o: JSONObject) = Question(
            o.getString("key"), o.optString("title"), o.optString("description"),
            o.optLong("begin"), o.optLong("end"), o.optLong("askAt"),
        )
    }
}

/** Réponse donnée à une question, gardée pour l'historique affiché dans l'appli. */
data class Answer(
    val key: String, val title: String, val end: Long, val done: Boolean, val taskCreated: Boolean,
    val ticked: Boolean = false,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("relance", Context.MODE_PRIVATE)

    var account: String?
        get() = sp.getString("account", null)
        set(v) = sp.edit().putString("account", v).apply()

    var tasksConnected: Boolean
        get() = sp.getBoolean("tasksConnected", false)
        set(v) = sp.edit().putBoolean("tasksConnected", v).apply()

    var askEvents: Boolean
        get() = sp.getBoolean("askEvents", true)
        set(v) = sp.edit().putBoolean("askEvents", v).apply()

    var remindTasks: Boolean
        get() = sp.getBoolean("remindTasks", true)
        set(v) = sp.edit().putBoolean("remindTasks", v).apply()

    /** Crée une tâche Google Tasks quand on répond « Pas fait » à un événement. */
    var createTaskWhenNotDone: Boolean
        get() = sp.getBoolean("createTaskWhenNotDone", true)
        set(v) = sp.edit().putBoolean("createTaskWhenNotDone", v).apply()

    var reminderTimes: String
        get() = sp.getString("reminderTimes", "9h, 18h")!!
        set(v) = sp.edit().putString("reminderTimes", v).apply()

    /** Début et fin de la journée : pas de notification en dehors. */
    var dayStart: String
        get() = sp.getString("dayStart", "8h")!!
        set(v) = sp.edit().putString("dayStart", v).apply()

    var dayEnd: String
        get() = sp.getString("dayEnd", "21h")!!
        set(v) = sp.edit().putString("dayEnd", v).apply()

    /** Attendre que l'agenda soit libre (aucun événement en cours) pour notifier. */
    var waitUntilFree: Boolean
        get() = sp.getBoolean("waitUntilFree", true)
        set(v) = sp.edit().putBoolean("waitUntilFree", v).apply()

    /** Nombre de jours à l'avance pour les tâches « à venir ». */
    var horizonDays: Int
        get() = sp.getInt("horizonDays", 7)
        set(v) = sp.edit().putInt("horizonDays", v).apply()

    /** Rappeler aussi quelques tâches sans date à chaque relance. */
    var remindUndated: Boolean
        get() = sp.getBoolean("remindUndated", true)
        set(v) = sp.edit().putBoolean("remindUndated", v).apply()

    var lastCalendarCheck: Long
        get() = sp.getLong("lastCalendarCheck", 0)
        set(v) = sp.edit().putLong("lastCalendarCheck", v).apply()

    var lastSlotHandled: Long
        get() = sp.getLong("lastSlotHandled", 0)
        set(v) = sp.edit().putLong("lastSlotHandled", v).apply()

    var rotation: Int
        get() = sp.getInt("rotation", 0)
        set(v) = sp.edit().putInt("rotation", v).apply()

    /** Agendas surveillés (identifiants). null = choix par défaut (agendas modifiables). */
    var chosenCalendars: Set<String>?
        get() = sp.getStringSet("chosenCalendars", null)
        set(v) = sp.edit().putStringSet("chosenCalendars", v).apply()

    // --- Questions remises à plus tard ---

    @Synchronized
    fun snoozed(): List<Question> {
        val arr = JSONArray(sp.getString("snoozed", "[]"))
        return (0 until arr.length()).map { Question.fromJson(arr.getJSONObject(it)) }
    }

    @Synchronized
    fun setSnoozed(list: List<Question>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("snoozed", arr.toString()).apply()
    }

    @Synchronized
    fun snooze(q: Question) = setSnoozed(snoozed().filter { it.key != q.key } + q)

    // --- Tâches créées après un « Pas fait » : tâche → événement d'origine ---

    @Synchronized
    fun taskLinks(): Map<String, Question> {
        val o = JSONObject(sp.getString("taskLinks", "{}")!!)
        return o.keys().asSequence().associateWith { Question.fromJson(o.getJSONObject(it)) }
    }

    @Synchronized
    fun setTaskLinks(links: Map<String, Question>) {
        val o = JSONObject()
        links.forEach { (k, q) -> o.put(k, q.toJson()) }
        sp.edit().putString("taskLinks", o.toString()).apply()
    }

    @Synchronized
    fun linkTask(taskId: String, q: Question) = setTaskLinks(taskLinks() + (taskId to q))

    @Synchronized
    fun unlinkTask(taskId: String) = setTaskLinks(taskLinks() - taskId)

    // --- Historique des réponses ---

    @Synchronized
    fun history(): List<Answer> {
        val arr = JSONArray(sp.getString("history", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Answer(o.getString("key"), o.optString("title"), o.optLong("end"),
                o.optBoolean("done"), o.optBoolean("taskCreated"), o.optBoolean("ticked"))
        }
    }

    @Synchronized
    fun addAnswer(a: Answer) {
        val list = (listOf(a) + history().filter { it.key != a.key }).take(100)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("key", it.key).put("title", it.title).put("end", it.end)
                .put("done", it.done).put("taskCreated", it.taskCreated).put("ticked", it.ticked))
        }
        sp.edit().putString("history", arr.toString()).apply()
    }
}
