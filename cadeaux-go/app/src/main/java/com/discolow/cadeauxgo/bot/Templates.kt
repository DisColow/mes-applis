package com.discolow.cadeauxgo.bot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.discolow.cadeauxgo.vision.Pattern
import com.discolow.cadeauxgo.vision.Resize
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

enum class Category { OPEN, SEND, OTHER }

/**
 * Ce que représente un motif appris. L'ordre de déclaration = la priorité quand plusieurs
 * motifs sont visibles en même temps.
 */
enum class Role(val label: String, val help: String, val category: Category) {
    STOP(
        "Arrêter si vu",
        "Facultatif. Un écran qui doit arrêter la routine (ex. « Sac plein »).",
        Category.OTHER,
    ),
    LIMIT_OPEN(
        "Limite d'ouverture atteinte",
        "Facultatif. Le message « tu as ouvert le maximum de cadeaux aujourd'hui ». Entoure le texte, puis touche le bouton OK pour indiquer où cliquer. Ensuite Cadeaux GO ne fait plus qu'envoyer.",
        Category.OPEN,
    ),
    OPEN_CONFIRM(
        "Bouton « OUVRIR »",
        "Le bouton vert « OUVRIR » sous la carte, sur l'écran où le cadeau reçu est affiché en grand (on y arrive en touchant un ami qui t'a envoyé un cadeau dans la liste).",
        Category.OPEN,
    ),
    SEND_CONFIRM(
        "Bouton « ENVOYER »",
        "Le bouton vert « ENVOYER » sous la carte du cadeau choisi.",
        Category.SEND,
    ),
    GIFT_ITEM(
        "Cadeau à envoyer (nœud rose)",
        "Écran « Quel cadeau veux-tu envoyer ? » : entoure le NŒUD ROSE au-dessus d'un cadeau (pas la photo, elle change d'un cadeau à l'autre).",
        Category.SEND,
    ),
    OPEN_BUTTON(
        "Cadeau reçu (sur le profil)",
        "Facultatif, rarement utile : dans la liste, toucher un ami qui a envoyé un cadeau ouvre directement l'écran « OUVRIR ». À apprendre seulement si un cadeau reçu apparaît sur le profil et qu'il faut le toucher.",
        Category.OPEN,
    ),
    SEND_BUTTON(
        "Bouton « CADEAU » du profil (en couleur)",
        "Sur le profil d'un ami : l'icône « CADEAU » à gauche de « ÉCHANGE LOCAL », quand elle est en couleur (elle devient grise une fois le cadeau envoyé). Entoure l'icône et son texte.",
        Category.SEND,
    ),
    CLOSE(
        "Fermer / Continuer",
        "Ce qu'il faut toucher pour passer les écrans après l'ouverture : le rond vert à croix en bas de l'écran des objets reçus (Potion, Super Ball…). Ignoré sur le profil et dans la liste. Tu peux en apprendre plusieurs.",
        Category.OTHER,
    ),
    PROFILE(
        "Repère : profil d'un ami",
        "OBLIGATOIRE. Un élément toujours visible sur le profil d'un ami et nulle part ailleurs, par exemple l'icône et le texte « ÉCHANGE À DISTANCE ». Ni nom, ni chiffre, ni cœurs (ils changent d'un ami à l'autre).",
        Category.OTHER,
    ),
    PROFILE_CLOSE(
        "Fermer le profil (retour à la liste)",
        "Facultatif. Le rond vert avec une croix en bas du profil d'un ami. Sinon, Cadeaux GO utilise le bouton retour du téléphone.",
        Category.OTHER,
    ),
    LIST(
        "Repère : liste d'amis",
        "OBLIGATOIRE en mode liste. Un élément toujours visible sur la liste d'amis et pas sur le profil (ex. le titre « AMIS » ou la barre de recherche).",
        Category.OTHER,
    ),
    LIST_ROW(
        "Liste : repère de ligne (cœur)",
        "OBLIGATOIRE pour envoyer. Le premier cœur d'amitié sous la photo d'un ami : il sert à repérer chaque ligne. Entoure UN seul cœur, puis touche la photo de l'ami pour indiquer où cliquer. Si tu as des amis sans aucun cœur plein, apprends aussi un cœur vide.",
        Category.OTHER,
    ),
    LIST_GIFT(
        "Liste : cadeau reçu",
        "Le paquet cadeau sur la ligne d'un ami qui t'a envoyé un cadeau. Entoure juste le paquet (pas le texte). Tu peux en apprendre plusieurs (ex. avec autocollant).",
        Category.OPEN,
    ),
    LIST_SENT(
        "Liste : déjà envoyé (flèche grise)",
        "La petite flèche grise sur la photo d'un ami à qui tu as déjà envoyé un cadeau. Entoure juste le rond gris. Les amis SANS cette flèche recevront un cadeau.",
        Category.SEND,
    ),

    // ---- Transfert de Pokémon (écran « Pokémon » avec une recherche)
    TR_PAGE(
        "Transfert : page POKÉMON",
        "L'onglet « POKÉMON » en haut de la page de tes Pokémon ; le point de clic est la barre de recherche juste en dessous.",
        Category.OTHER,
    ),
    TR_LIST(
        "Transfert : repère du stockage",
        "La ligne « AFFICHER LES ÉVOLUTIONS » sous la barre de recherche de tes Pokémon.",
        Category.OTHER,
    ),
    TR_CELL(
        "Transfert : case d'un Pokémon (« PC »)",
        "Le petit « PC » devant le nombre de PC d'une case (sans les chiffres).",
        Category.OTHER,
    ),
    TR_SELECT(
        "Transfert : mode sélection",
        "Le bandeau « TOUT SÉLECTIONNER » en haut, après un appui long sur un Pokémon.",
        Category.OTHER,
    ),
    TR_BUTTON(
        "Transfert : bouton « TRANSFÉRER (n) »",
        "Le bouton blanc « TRANSFÉRER » en bas en mode sélection (sans le nombre).",
        Category.OTHER,
    ),
    TR_CONFIRM(
        "Transfert : fenêtre de confirmation",
        "Le texte « Veux-tu vraiment transférer » (sans le nombre).",
        Category.OTHER,
    ),
    TR_CONFIRM_BUTTON(
        "Transfert : bouton vert « TRANSFÉRER »",
        "Le bouton vert de la fenêtre de confirmation.",
        Category.OTHER,
    ),
    TR_CANCEL(
        "Transfert : « ANNULER »",
        "Le lien « ANNULER » de la fenêtre de confirmation.",
        Category.OTHER,
    ),
    ;

    companion object {
        /** Ordre d'affichage : l'ordre où l'on rencontre les écrans dans le jeu. */
        val UI_ORDER = listOf(
            LIST, LIST_ROW, LIST_GIFT, LIST_SENT,
            PROFILE, OPEN_BUTTON, SEND_BUTTON, PROFILE_CLOSE,
            OPEN_CONFIRM, CLOSE, GIFT_ITEM, SEND_CONFIRM,
            LIMIT_OPEN, STOP,
            TR_PAGE, TR_LIST, TR_CELL, TR_SELECT, TR_BUTTON, TR_CONFIRM, TR_CONFIRM_BUTTON, TR_CANCEL,
        )
    }
}

/** Un motif appris. Coordonnées en pixels réels de l'écran. */
data class Tpl(
    val id: String,
    val role: Role,
    val x: Int, val y: Int, val w: Int, val h: Int,
    /** Point à toucher, relatif au coin haut-gauche du motif. */
    val tapX: Int, val tapY: Int,
    val screenW: Int, val screenH: Int,
)

/**
 * Motif prêt à l'emploi : [pattern] à l'échelle de travail, point de clic ([tapX], [tapY]) en
 * pixels réels de l'écran, relatif au coin du motif.
 */
class LoadedTpl(
    val tpl: Tpl,
    val pattern: Pattern,
    val tapX: Float,
    val tapY: Float,
    val colorTol: Int = 40,
    val builtin: Boolean = false,
)

object TemplateStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "templates").apply { mkdirs() }
    private fun index(ctx: Context) = File(dir(ctx), "index.json")
    fun image(ctx: Context, id: String) = File(dir(ctx), "$id.png")

    @Synchronized
    fun all(ctx: Context): List<Tpl> {
        val f = index(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val role = runCatching { Role.valueOf(o.getString("role")) }.getOrNull() ?: return@mapNotNull null
                Tpl(
                    o.getString("id"), role,
                    o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"),
                    o.getInt("tapX"), o.getInt("tapY"),
                    o.getInt("screenW"), o.getInt("screenH"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun write(ctx: Context, list: List<Tpl>) {
        val a = JSONArray()
        list.forEach { t ->
            a.put(
                JSONObject()
                    .put("id", t.id).put("role", t.role.name)
                    .put("x", t.x).put("y", t.y).put("w", t.w).put("h", t.h)
                    .put("tapX", t.tapX).put("tapY", t.tapY)
                    .put("screenW", t.screenW).put("screenH", t.screenH),
            )
        }
        index(ctx).writeText(a.toString())
    }

    @Synchronized
    fun add(ctx: Context, role: Role, crop: Bitmap, x: Int, y: Int, tapX: Int, tapY: Int, screenW: Int, screenH: Int): Tpl {
        val t = Tpl(UUID.randomUUID().toString(), role, x, y, crop.width, crop.height, tapX, tapY, screenW, screenH)
        image(ctx, t.id).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        write(ctx, all(ctx) + t)
        return t
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        image(ctx, id).delete()
        write(ctx, all(ctx).filter { it.id != id })
    }

    @Synchronized
    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    private fun pixels(bmp: Bitmap): IntArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px
    }

    /**
     * Charge les motifs appris + (si [withBuiltin]) les motifs intégrés, mis à l'échelle de la
     * capture : un motif appris sur un écran de largeur L est réduit d'un facteur [workW] / L.
     * [screenW]/[screenH] = taille réelle de l'écran actuel.
     */
    fun load(ctx: Context, screenW: Int, screenH: Int, workW: Int, withBuiltin: Boolean): List<LoadedTpl> {
        val portrait = screenH >= screenW
        val mine = all(ctx).mapNotNull { t ->
            // Motif appris dans l'autre orientation : inutilisable.
            if ((t.screenH >= t.screenW) != portrait) return@mapNotNull null
            val bmp = BitmapFactory.decodeFile(image(ctx, t.id).path) ?: return@mapNotNull null
            val p = Resize.pattern(pixels(bmp), bmp.width, bmp.height, t.screenW, workW)
            bmp.recycle()
            val s = screenW.toFloat() / t.screenW
            LoadedTpl(t, p, t.tapX * s, t.tapY * s)
        }
        if (!withBuiltin || !portrait) return mine
        val builtin = Builtin.all.mapNotNull { d ->
            val bmp = ctx.assets.open("builtin/${d.file}").use { BitmapFactory.decodeStream(it) } ?: return@mapNotNull null
            val p = Resize.pattern(pixels(bmp), bmp.width, bmp.height, d.screenW, workW)
            val s = screenW.toFloat() / d.screenW
            val t = Tpl(
                "builtin:${d.file}:${d.role}", d.role, 0, 0, bmp.width, bmp.height,
                d.tapX ?: (bmp.width / 2), d.tapY ?: (bmp.height / 2), d.screenW, 0,
            )
            bmp.recycle()
            LoadedTpl(t, p, t.tapX * s, t.tapY * s, d.colorTol, builtin = true)
        }
        return mine + builtin
    }
}

class BotSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var doOpen: Boolean
        get() = p.getBoolean("doOpen", true)
        set(v) = p.edit().putBoolean("doOpen", v).apply()
    var doSend: Boolean
        get() = p.getBoolean("doSend", true)
        set(v) = p.edit().putBoolean("doSend", v).apply()
    /** Ami suivant = glisser du doigt de droite à gauche. */
    var swipeLeft: Boolean
        get() = p.getBoolean("swipeLeft", true)
        set(v) = p.edit().putBoolean("swipeLeft", v).apply()
    /** Seuil de ressemblance en % (NCC). */
    var threshold: Int
        get() = p.getInt("threshold", 82)
        set(v) = p.edit().putInt("threshold", v).apply()
    /** Attente après chaque action, en ms. */
    var delayMs: Int
        get() = p.getInt("delayMs", 1000)
        set(v) = p.edit().putInt("delayMs", v).apply()
    var maxFriends: Int
        get() = p.getInt("maxFriends", 400)
        set(v) = p.edit().putInt("maxFriends", v).apply()
    /** true = parcourt la liste d'amis ; false = glisse de profil en profil. */
    var listMode: Boolean
        get() = p.getBoolean("listMode", true)
        set(v) = p.edit().putBoolean("listMode", v).apply()
    /** Utiliser les boutons intégrés (pas de calibrage). */
    var useBuiltin: Boolean
        get() = p.getBoolean("useBuiltin", true)
        set(v) = p.edit().putBoolean("useBuiltin", v).apply()
    // ---- Transfert
    /** Nombre total de Pokémon à transférer par lancement. */
    var transferTarget: Int
        get() = p.getInt("transferTarget", 50)
        set(v) = p.edit().putInt("transferTarget", v).apply()
    /** Nombre maximum de Pokémon par transfert (par fenêtre de confirmation). */
    var transferBatch: Int
        get() = p.getInt("transferBatch", 50)
        set(v) = p.edit().putInt("transferBatch", v).apply()
    /** Essai à blanc : tout sauf la confirmation finale (« ANNULER » à la place). */
    var transferDryRun: Boolean
        get() = p.getBoolean("transferDryRun", true)
        set(v) = p.edit().putBoolean("transferDryRun", v).apply()
    var transferMaxCp: Int
        get() = p.getInt("transferMaxCp", 1500)
        set(v) = p.edit().putInt("transferMaxCp", v).apply()
    var transferMaxHp: Int
        get() = p.getInt("transferMaxHp", 120)
        set(v) = p.edit().putInt("transferMaxHp", v).apply()
    /** Espèces à garder, séparées par des virgules. */
    var transferKeepSpecies: String
        get() = p.getString("transferKeepSpecies", TransferSearch.DEFAULT_KEEP) ?: TransferSearch.DEFAULT_KEEP
        set(v) = p.edit().putString("transferKeepSpecies", v).apply()
    /** Recherche complète écrite à la main (vide = générée à partir des réglages). */
    var transferCustomSearch: String
        get() = p.getString("transferCustomSearch", "") ?: ""
        set(v) = p.edit().putString("transferCustomSearch", v).apply()

    fun transferSearch(): String = transferCustomSearch.trim().ifEmpty {
        TransferSearch.build(transferMaxCp, transferMaxHp, transferKeepSpecies)
    }

    var showEverywhere: Boolean
        get() = p.getBoolean("showEverywhere", false)
        set(v) = p.edit().putBoolean("showEverywhere", v).apply()
}
