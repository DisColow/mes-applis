package fr.mesapplis.relance

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class RelanceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        Checks.schedule(this)
    }
}

/** Boutons des notifications : on retire la notification tout de suite, le travail se fait ensuite. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_NOTIF_ID = "notifId"
        const val EVENT_DONE = "fr.mesapplis.relance.EVENT_DONE"
        const val EVENT_NOT_DONE = "fr.mesapplis.relance.EVENT_NOT_DONE"
        const val EVENT_SNOOZE = "fr.mesapplis.relance.EVENT_SNOOZE"
        const val TASK_DONE = "fr.mesapplis.relance.TASK_DONE"
        const val TASK_TOMORROW = "fr.mesapplis.relance.TASK_TOMORROW"
        const val TASK_NEXT_WEEK = "fr.mesapplis.relance.TASK_NEXT_WEEK"

        fun eventIntent(action: String, q: Question) = Intent(action)
            .putExtra("key", q.key).putExtra("title", q.title).putExtra("description", q.description)
            .putExtra("begin", q.begin).putExtra("end", q.end)

        fun taskIntent(action: String, t: GTask) = Intent(action)
            .putExtra("listId", t.listId).putExtra("taskId", t.id).putExtra("title", t.title)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Notifier.cancel(ctx, intent.getIntExtra(EXTRA_NOTIF_ID, 0))
        handle(ctx, intent)
        Notifier.tidyGroups(ctx)
        if (intent.action == EVENT_SNOOZE) Scheduler.schedule(ctx)
    }

    /** Traite une réponse (depuis une notification ou depuis l'écran de l'appli). */
    fun handle(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        val prefs = Prefs(ctx)
        when (action) {
            EVENT_SNOOZE -> prefs.snooze(question(intent).copy(askAt = System.currentTimeMillis() + 3600_000L))
            EVENT_DONE -> {
                // Coche dans l'agenda tout de suite (pas besoin d'internet) ; les tâches ensuite.
                Sync.eventDone(ctx, question(intent))
                if (prefs.tasksConnected) enqueue(ctx, action, intent)
            }
            EVENT_NOT_DONE -> {
                val createTask = prefs.tasksConnected && prefs.createTaskWhenNotDone
                prefs.addAnswer(answer(intent, done = false, taskCreated = createTask))
                if (createTask) enqueue(ctx, action, intent)
            }
            TASK_DONE, TASK_TOMORROW, TASK_NEXT_WEEK -> enqueue(ctx, action, intent)
        }
    }

    private fun question(i: Intent) = Question(
        i.getStringExtra("key") ?: "", i.getStringExtra("title") ?: "", i.getStringExtra("description") ?: "",
        i.getLongExtra("begin", 0), i.getLongExtra("end", 0),
    )

    private fun answer(i: Intent, done: Boolean, taskCreated: Boolean) = Answer(
        i.getStringExtra("key") ?: "", i.getStringExtra("title") ?: "", i.getLongExtra("end", 0), done, taskCreated,
    )

    private fun enqueue(ctx: Context, action: String, i: Intent) {
        val data = Data.Builder().putString("action", action)
        i.extras?.keySet()?.forEach { k ->
            when (val v = i.extras?.get(k)) {
                is String -> data.putString(k, v)
                is Long -> data.putLong(k, v)
            }
        }
        WorkManager.getInstance(ctx).enqueue(
            OneTimeWorkRequestBuilder<ActionWorker>()
                .setInputData(data.build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }
}

/** Exécute dans Google Tasks l'action choisie depuis une notification. */
class ActionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val d = inputData
        return try {
            val api = GoogleAuth.api(ctx)
            val today = LocalDate.now()
            when (d.getString("action")) {
                ActionReceiver.TASK_DONE -> {
                    api.complete(d.getString("listId")!!, d.getString("taskId")!!)
                    Sync.taskCompleted(ctx, d.getString("taskId")!!)
                }
                ActionReceiver.EVENT_DONE -> Sync.completeTasksOf(ctx, api, Question(
                    d.getString("key").orEmpty(), d.getString("title").orEmpty(), "",
                    d.getLong("begin", 0), d.getLong("end", 0)))
                ActionReceiver.TASK_TOMORROW -> api.setDue(d.getString("listId")!!, d.getString("taskId")!!, today.plusDays(1))
                ActionReceiver.TASK_NEXT_WEEK -> api.setDue(d.getString("listId")!!, d.getString("taskId")!!, today.plusDays(7))
                ActionReceiver.EVENT_NOT_DONE -> {
                    val begin = Instant.ofEpochMilli(d.getLong("begin", 0)).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    val notes = buildString {
                        append("À reprendre — prévu dans l'agenda le ").append(Logic.dayTime(begin)).append(".")
                        val desc = d.getString("description").orEmpty()
                        if (desc.isNotEmpty()) append("\n\n").append(desc)
                    }
                    val id = api.create(d.getString("title") ?: "À reprendre", notes, today)
                    // On retient le lien pour cocher l'événement quand la tâche sera faite.
                    if (id.isNotEmpty()) Prefs(ctx).linkTask(id, Question(
                        d.getString("key").orEmpty(), d.getString("title").orEmpty(), "",
                        d.getLong("begin", 0), d.getLong("end", 0)))
                }
            }
            Result.success()
        } catch (e: NeedsAuthException) {
            Notifier.needsAuth(ctx)
            Result.failure()
        } catch (e: IOException) {
            if (runAttemptCount < 5) Result.retry() else {
                Notifier.info(ctx, "Google Tasks injoignable", "L'action « ${d.getString("title")} » n'a pas pu être enregistrée : ${e.message}")
                Result.failure()
            }
        }
    }
}
