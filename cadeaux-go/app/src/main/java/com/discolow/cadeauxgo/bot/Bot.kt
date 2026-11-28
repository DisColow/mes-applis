package com.discolow.cadeauxgo.bot

import com.discolow.cadeauxgo.vision.Frame
import com.discolow.cadeauxgo.vision.Match
import com.discolow.cadeauxgo.vision.Matcher
import kotlin.math.abs

/** Ce dont la routine a besoin : regarder l'écran et le toucher. */
interface BotHost {
    /** Capture réduite à la résolution de travail, ou null si indisponible. */
    fun capture(): Frame?
    /** Coordonnées en pixels réels de l'écran. */
    fun tap(x: Float, y: Float): Boolean
    fun swipeNextFriend(left: Boolean): Boolean
    /** Fait défiler une liste : [down] = vers la suite de la liste. */
    fun scrollList(down: Boolean, fast: Boolean): Boolean
    /**
     * Glisse le doigt verticalement de [fromY] à [toY] (pixels réels), puis le garde immobile avant de
     * lâcher : la liste s'arrête où le doigt s'arrête.
     */
    fun drag(fromY: Float, toY: Float): Boolean
    /** Appui long (ms) en pixels réels. */
    fun longPress(x: Float, y: Float, durationMs: Long = 900): Boolean
    /** Tape un texte dans le champ de saisie ouvert, le vérifie et valide. */
    fun typeText(text: String): Boolean
    fun back()
    fun status(text: String)
}

private class Abort(message: String) : Exception(message)

private typealias Hit = Pair<LoadedTpl, Match>

/**
 * La routine.
 *
 * Mode liste (par défaut), en un seul passage : dans la liste d'amis, repère chaque ligne (cœur),
 * et visite les amis qui ont envoyé un cadeau (paquet) et/ou à qui on n'a pas encore envoyé
 * (pas de flèche grise). Sur le profil : ouvre, puis envoie, puis revient ; fait défiler jusqu'en bas.
 *
 * Mode profil : depuis le profil d'un ami, ouvre/envoie puis glisse vers l'ami suivant.
 */
class Bot(
    private val host: BotHost,
    private val settings: BotSettings,
    private val loader: (Frame) -> List<LoadedTpl>,
    private val mode: Mode = Mode.GIFTS,
) {
    @Volatile
    var running = true
        private set

    var opened = 0
        private set
    var sent = 0
        private set
    enum class Mode { GIFTS, TRANSFER }

    var transferred = 0
        private set
    private var selectedNow = 0

    var visited = 0
        private set

    private var tpls: List<LoadedTpl> = emptyList()
    private val threshold = settings.threshold / 100.0
    private val delay = settings.delayMs.toLong()
    private val poll = 150L

    // État « toucher sans effet » : un motif touché deux fois sans que l'écran change est ignoré
    // jusqu'au prochain ami (ex. bouton grisé reconnu par erreur).
    private val ignored = HashSet<String>()
    private val ineffective = HashMap<String, Int>()
    private var pendingTap: Pair<String, Frame>? = null

    fun stop() {
        running = false
    }

    fun summary() = if (mode == Mode.TRANSFER) {
        "$transferred transférés · $selectedNow sélectionnés"
    } else {
        "$opened ouverts · $sent envoyés · $visited amis visités"
    }

    /** Lance la routine (bloquant) et renvoie la raison de l'arrêt. */
    fun run(): String = try {
        loop()
    } catch (e: Abort) {
        e.message ?: "Arrêt"
    } catch (e: InterruptedException) {
        "Arrêté"
    } catch (e: Exception) {
        "Erreur : ${e.message ?: e.javaClass.simpleName}"
    } finally {
        running = false
    }

    // ---------------------------------------------------------------- outils

    private fun checkRunning() {
        if (!running) throw Abort("Arrêté")
    }

    private fun sleep(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (running && System.currentTimeMillis() < end) Thread.sleep(50)
        checkRunning()
    }

    // Dernière capture : réutilisée comme point de départ de stableFrame() (une capture de moins).
    private var last: Frame? = null
    private var lastAt = 0L

    private fun capture(): Frame {
        repeat(5) {
            checkRunning()
            host.capture()?.let {
                last = it; lastAt = System.currentTimeMillis()
                return it
            }
            sleep(300)
        }
        throw Abort("Capture d'écran impossible")
    }

    /**
     * Après une action : attend que l'écran réagisse (change par rapport à [before]), au plus [delay] ms.
     * Plus rapide qu'une attente fixe quand le jeu répond vite.
     */
    private fun settle(before: Frame?) {
        if (before == null) { sleep(delay); return }
        val end = System.currentTimeMillis() + delay
        sleep(120)
        while (System.currentTimeMillis() < end) {
            if (Matcher.changedFraction(before, capture()) > 0.02) return
        }
    }

    /** Motifs utiles à chaque routine (ceux de l'autre routine ne sont pas cherchés). */
    private val transferRoles = Role.entries.filter { it.name.startsWith("TR_") }
    private val giftRoles = Role.entries - transferRoles.toSet()

    /** Cherche en parallèle (tous les cœurs) les motifs de [roles] sur [f] : les appels suivants lisent le cache. */
    private fun scan(f: Frame, roles: Collection<Role>) {
        tpls.filter { it.tpl.role in roles }.parallelStream().forEach { t ->
            Matcher.findAll(f, t.pattern, threshold, t.colorTol)
        }
    }

    /** Attend que l'écran ne bouge plus (animations, défilement), sans bloquer indéfiniment. */
    private fun stableFrame(): Frame {
        // La capture prise juste avant (par settle()) sert de point de comparaison.
        var prev = last?.takeIf { System.currentTimeMillis() - lastAt < 700 } ?: capture()
        // 4 essais max : le profil a un avatar animé qui ne s'arrête jamais de bouger.
        repeat(4) {
            sleep(poll)
            val f = capture()
            if (Matcher.changedFraction(prev, f) <= 0.03) return f
            prev = f
        }
        return prev
    }

    private fun of(vararg roles: Role) = tpls.filter { it.tpl.role in roles }

    private fun present(f: Frame, role: Role) = of(role).any { Matcher.find(f, it.pattern, threshold, it.colorTol) != null }

    /** Reconnu même partiellement (transition, écran assombri…) : sert de garde-fou. */
    private fun nearly(f: Frame, role: Role) = of(role).any { Matcher.find(f, it.pattern, 0.6, it.colorTol) != null }

    /**
     * La croix ronde du bas est LA MÊME sur la liste d'amis (où elle ferme la liste), le profil et
     * l'écran des objets reçus. Avant de la toucher, nouvelle capture : jamais si la liste est
     * visible, même partiellement ; [onProfile] = on veut fermer un profil (il doit être affiché).
     */
    private fun safeToTapX(onProfile: Boolean): Boolean {
        val g = stableFrame()
        if (nearly(g, Role.LIST)) return false
        return if (onProfile) present(g, Role.PROFILE) else !nearly(g, Role.PROFILE)
    }

    /** Le motif le plus prioritaire (ordre des rôles) parmi [roles]. */
    private fun best(f: Frame, roles: List<Role>): Hit? {
        val hits = ArrayList<Hit>()
        for (t in tpls) {
            if (t.tpl.role !in roles || t.tpl.id in ignored) continue
            Matcher.find(f, t.pattern, threshold, t.colorTol)?.let { hits += t to it }
        }
        val hit = hits.minWithOrNull(compareBy<Hit>({ it.first.tpl.role.ordinal }, { -it.second.score }))
            ?: return null
        if (hit.first.tpl.role == Role.GIFT_ITEM) {
            // Toujours le premier cadeau de la liste (en haut à gauche), pas le plus ressemblant.
            val t = hit.first
            val all = Matcher.findAll(f, t.pattern, threshold, t.colorTol)
            Matcher.firstInReadingOrder(all, t.pattern.h)?.let { return t to it }
        }
        return hit
    }

    private fun tap(hit: Hit, f: Frame) {
        val (t, m) = hit
        host.tap(m.x / f.factor + t.tapX, m.y / f.factor + t.tapY)
        pendingTap = t.tpl.id to f
    }

    private fun checkPendingTap(f: Frame) {
        pendingTap?.let { (id, before) ->
            if (Matcher.changedFraction(before, f) < 0.01) {
                val c = (ineffective[id] ?: 0) + 1
                ineffective[id] = c
                if (c >= 2) ignored += id
            }
        }
        pendingTap = null
    }

    private fun newFriend() {
        ignored.clear()
        ineffective.clear()
        pendingTap = null
    }

    private fun label(hit: Hit) = "${hit.first.tpl.role.label} (${(hit.second.score * 100).toInt()} %)"

    // ---------------------------------------------------------------- démarrage

    private fun loop(): String {
        host.status("Démarrage…")
        val first = capture()
        tpls = loader(first)
        if (tpls.isEmpty()) return "Aucun motif appris pour cette taille d'écran"
        if (mode == Mode.TRANSFER) return transferLoop()
        if (of(Role.PROFILE).isEmpty()) return "Apprends d'abord le repère « profil d'un ami »"
        if (!settings.listMode) return swipeMode()

        if (of(Role.LIST).isEmpty()) return "Apprends d'abord le repère « liste d'amis »"
        var open = settings.doOpen
        var send = settings.doSend
        val notes = ArrayList<String>()
        if (open && of(Role.LIST_GIFT).isEmpty()) {
            open = false; notes += "Ouverture ignorée : apprends « Liste : cadeau reçu »"
        }
        if (send && (of(Role.LIST_ROW).isEmpty() || of(Role.LIST_SENT).isEmpty())) {
            send = false; notes += "Envoi ignoré : apprends « Liste : repère de ligne » et « Liste : déjà envoyé »"
        }
        if (!open && !send) return notes.joinToString("\n").ifEmpty { "Rien à faire (ouverture et envoi désactivés)" }
        notes.add(0, listPass(open, send))
        return notes.joinToString("\n")
    }

    // ---------------------------------------------------------------- défilement

    /** Rapport déplacement de la liste / déplacement du doigt, mesuré à chaque défilement. */
    private var scrollRatio = 1f

    /**
     * Fait défiler la liste de [wanted] pixels (de travail) vers le haut d'un seul geste, pour faire
     * sortir de l'écran tout ce qui a déjà été traité. Mesure ensuite le déplacement réel (une bande de
     * l'ancienne capture retrouvée dans la nouvelle), recalibre le rapport doigt/liste, et remonte un
     * peu si la liste est allée trop loin (une ligne ne doit jamais être sautée).
     * Renvoie le déplacement mesuré, 0 si la liste n'a pas bougé (fin), null s'il est inconnu.
     */
    private fun scrollContent(f: Frame, wantedRaw: Int, pitch: Int): Int? {
        // Au plus 45 % de l'écran par geste : une partie du contenu doit rester visible avant et après
        // pour mesurer le déplacement réel (vérifié sur de vraies captures, ScrollTest).
        val wanted = wantedRaw.coerceAtMost((f.h * 0.45f).toInt())
        val h = f.srcH.toFloat()
        val start = h * 0.78f
        val travel = (wanted / f.factor / scrollRatio).coerceIn(h * 0.06f, h * 0.66f)
        host.drag(start, start - travel)
        val after = stableFrame()
        if (Matcher.changedFraction(f, after) < 0.01) return 0
        val shift = Matcher.verticalShift(f, after) ?: return null
        if (shift > 0) {
            val r = shift / f.factor / travel
            scrollRatio = (0.5f * scrollRatio + 0.5f * r).coerceIn(0.4f, 1.6f)
        }
        val excess = shift - wanted
        if (excess > pitch / 3) {
            // Trop loin : des lignes ont pu passer au-dessus sans être vues → on redescend de l'excédent.
            val back = (excess / f.factor / scrollRatio).coerceAtMost(h * 0.5f)
            host.drag(h * 0.35f, h * 0.35f + back)
            stableFrame()
            return wanted
        }
        return shift
    }

    /** Écart médian entre des positions verticales triées (hauteur d'une ligne), ou [fallback]. */
    private fun pitchOf(ys: List<Int>, fallback: Int): Int {
        val gaps = ys.zipWithNext { a, b -> b - a }.filter { it > 4 }.sorted()
        return if (gaps.isEmpty()) fallback else gaps[gaps.size / 2]
    }

    // ---------------------------------------------------------------- mode liste

    private class Target(val x: Float, val y: Float, val workY: Int)

    private fun boxes(f: Frame, role: Role) = of(role).flatMap { t ->
        Matcher.findAll(f, t.pattern, threshold, t.colorTol).map { m -> FriendRows.Box(m.x, m.y, t.pattern.w, t.pattern.h) to t }
    }

    /** Lignes de la liste à visiter, de haut en bas (coordonnées réelles du point à toucher). */
    private fun targets(f: Frame, open: Boolean, send: Boolean): List<Target> {
        val gifts = boxes(f, Role.LIST_GIFT)
        if (of(Role.LIST_ROW).isEmpty()) {
            // Sans repère de ligne : on touche directement les paquets cadeau (ouverture seule).
            return gifts.map { (b, t) -> Target(b.x / f.factor + t.tapX, b.y / f.factor + t.tapY, b.cy) }
                .sortedBy { it.workY }
        }
        val hearts = boxes(f, Role.LIST_ROW)
        val rows = FriendRows.analyze(hearts.map { it.first }, gifts.map { it.first }, boxes(f, Role.LIST_SENT).map { it.first }, f.h)
        return rows.filter { it.needsVisit(open, send) }.map { r ->
            // Point de clic appris avec le repère (ex. la photo de l'ami).
            val t = hearts.minBy { abs(it.first.x - r.x) + abs(it.first.y - r.y) }.second
            Target(r.x / f.factor + t.tapX, r.y / f.factor + t.tapY, r.y)
        }
    }

    /**
     * Un seul passage : pour chaque ami de la liste qui a envoyé un cadeau et/ou à qui on n'a pas
     * encore envoyé, ouvre le profil, ouvre puis envoie, revient ; fait défiler jusqu'en bas.
     */
    private fun listPass(open: Boolean, send: Boolean): String {
        val otherRoles = buildList {
            add(Role.STOP)
            if (open) addAll(listOf(Role.LIMIT_OPEN, Role.OPEN_CONFIRM))
            if (send) addAll(listOf(Role.SEND_CONFIRM, Role.GIFT_ITEM))
        }

        // Ordonnées (résolution de travail) des lignes à ignorer à la position de défilement actuelle :
        // l'ami a été visité mais la ligne réclame toujours une visite (limite, envoi impossible…).
        val skip = ArrayList<Int>()
        var visitY: Int? = null
        var visits = 0
        var limit = false
        var actions = 0
        var closes = 0
        var idle = 0
        var lost = 0
        var seenList = false
        val start = System.currentTimeMillis()
        val tol = 12
        // Visite d'un ami en cours (on a touché sa ligne et pas encore revu la liste).
        var inVisit = false
        // « OUVRIR » vient d'être touché : l'écran des objets reçus peut demander la croix.
        var rewardPending = false
        // Moment où on a touché la croix du profil : on attend qu'il disparaisse.
        var profileClosedAt = 0L

        while (true) {
            checkRunning()
            val f = stableFrame()
            scan(f, giftRoles)
            checkPendingTap(f)
            val onProfile = present(f, Role.PROFILE)
            val onList = !onProfile && present(f, Role.LIST)

            if (onList) {
                seenList = true
                idle = 0; lost = 0; closes = 0
                val canOpen = open && !limit
                if (!canOpen && !send) return "limite d'ouverture atteinte ($visits amis)"
                val todo = targets(f, canOpen, send)
                visitY?.let { vy -> if (todo.any { abs(it.workY - vy) < tol }) skip += vy }
                visitY = null
                inVisit = false
                rewardPending = false
                val next = todo.firstOrNull { c -> skip.none { abs(c.workY - it) < tol } }
                if (next != null) {
                    if (visits >= settings.maxFriends) return "nombre maximum d'amis atteint ($visits)"
                    host.status("Ami ${visits + 1}…\n${summary()}")
                    newFriend()
                    host.tap(next.x, next.y)
                    inVisit = true
                    visitY = next.workY
                    visits++
                    visited++
                    actions = 0
                    settle(f)
                    continue
                }
                // Défilement long : la dernière ligne analysée remonte tout en haut, les suivantes arrivent.
                host.status("Défilement…\n${summary()}")
                val ys = if (of(Role.LIST_ROW).isEmpty()) emptyList() else FriendRows.analyze(
                    boxes(f, Role.LIST_ROW).map { it.first }, emptyList(), emptyList(), f.h,
                ).map { it.y }
                val pitch = pitchOf(ys, (f.h * 0.15f).toInt())
                val top = (f.h * 0.15f).toInt()
                val wanted = if (ys.isEmpty()) (f.h * 0.3f).toInt() else maxOf(ys.last() - top + 4, pitch / 2)
                if (scrollContent(f, wanted, pitch) == 0) return "fin de la liste"
                skip.clear()
                continue
            }

            if (onProfile) {
                idle = 0; lost = 0
                rewardPending = false
                // On vient de fermer ce profil : il est peut-être encore en train de disparaître.
                // Surtout ne pas retoucher la croix, elle fermerait la liste d'amis.
                if (System.currentTimeMillis() - profileClosedAt < 4000) {
                    sleep(poll); continue
                }
                // D'abord ouvrir le cadeau reçu, ensuite envoyer.
                val roles = buildList {
                    if (open && !limit) add(Role.OPEN_BUTTON)
                    if (send) add(Role.SEND_BUTTON)
                }
                val btn = if (actions >= 15) null else best(f, roles)
                if (btn != null) {
                    host.status("${label(btn)}\n${summary()}")
                    tap(btn, f)
                    actions++
                    settle(f)
                    continue
                }
                // Plus rien à faire sur ce profil → retour à la liste.
                closes++
                if (closes > 3) throw Abort("Impossible de fermer le profil : apprends « Fermer le profil »")
                host.status("Retour à la liste…\n${summary()}")
                if (!safeToTapX(onProfile = true)) continue
                val close = best(f, listOf(Role.PROFILE_CLOSE))
                if (close != null) tap(close, f) else host.back()
                profileClosedAt = System.currentTimeMillis()
                settle(f)
                continue
            }

            // Liste reconnue partiellement (transition, défilement) : on attend, on ne touche à rien.
            if (nearly(f, Role.LIST)) {
                idle++
                if (idle > 8) throw Abort("Liste d'amis mal reconnue, je m'arrête par sécurité\n${summary()}")
                sleep(poll); continue
            }

            // Autre écran : cadeau en grand, sac de cadeaux, animations…
            var hit = if (actions < 25) best(f, otherRoles) else null
            // La croix seulement juste après « OUVRIR » (écran des objets reçus), et après vérification.
            if (hit == null && inVisit && rewardPending && actions < 25) {
                hit = best(f, listOf(Role.CLOSE))?.takeIf { safeToTapX(onProfile = false) }
            }
            if (hit != null) {
                idle = 0
                when (hit.first.tpl.role) {
                    Role.STOP -> throw Abort("Écran « arrêt » reconnu\n${summary()}")
                    Role.LIMIT_OPEN -> limit = true
                    Role.OPEN_CONFIRM -> { opened++; rewardPending = true }
                    Role.SEND_CONFIRM -> sent++
                    else -> {}
                }
                host.status("${label(hit)}\n${summary()}")
                tap(hit, f)
                actions++
                settle(f)
                continue
            }
            if (!seenList && System.currentTimeMillis() - start > 8000) {
                throw Abort("Liste d'amis introuvable. Ouvre la liste d'amis, puis relance.")
            }
            idle++
            if (idle < 3) continue
            lost++
            if (lost > 3) throw Abort("Écran inconnu, je m'arrête par sécurité\n${summary()}")
            // Retour seulement pendant la visite d'un ami : depuis la liste, il fermerait la liste.
            if (inVisit && safeToTapX(onProfile = false)) {
                host.status("Écran inconnu → retour")
                host.back()
            } else {
                host.status("Écran inconnu → j'attends")
            }
            idle = 0
            sleep(delay)
        }
    }

    // ---------------------------------------------------------------- transfert de Pokémon

    /**
     * Sur l'écran « Pokémon » avec la recherche déjà faite (triée par date de capture) :
     * appui long sur le premier → sélection case par case (vérifiée à la couleur) → TRANSFÉRER →
     * confirmation (ou ANNULER en essai à blanc) ; recommence jusqu'à l'objectif.
     */
    private fun transferLoop(): String {
        val needed = listOf(
            Role.TR_PAGE, Role.TR_LIST, Role.TR_CELL, Role.TR_SELECT, Role.TR_BUTTON,
            Role.TR_CONFIRM, Role.TR_CONFIRM_BUTTON, Role.TR_CANCEL,
        )
        needed.firstOrNull { of(it).isEmpty() }?.let { return "Motif manquant : ${it.label}" }
        val target = settings.transferTarget
        val batchMax = settings.transferBatch
        val dry = settings.transferDryRun
        var endReached = false
        var buttonMisses = 0
        var idle = 0
        // Cases touchées au tour précédent (coordonnées de travail) et nombre d'essais par case.
        var pending: List<StorageCell> = emptyList()
        val attempts = HashMap<Pair<Int, Int>, Int>()
        val tol = 12
        // La recherche est tapée par Cadeaux GO au début (elle remplace celle déjà dans la barre).
        var searchTyped = false

        while (true) {
            checkRunning()
            val f = stableFrame()
            scan(f, transferRoles)

            if (!searchTyped) {
                if (present(f, Role.TR_SELECT)) {
                    // Mode sélection déjà ouvert : on en sort avant de taper la recherche.
                    host.back(); sleep(delay); continue
                }
                val page = best(f, listOf(Role.TR_PAGE))
                if (page == null) {
                    idle++
                    if (idle > 6) throw Abort("Ouvre la page POKÉMON (tes Pokémon), puis relance ♻.")
                    sleep(poll); continue
                }
                idle = 0
                host.status("Recherche…")
                tap(page, f)
                sleep(900)
                if (!host.typeText(settings.transferSearch())) {
                    throw Abort("Je n'arrive pas à taper la recherche. Colle-la toi-même (bouton « Copier la recherche »), puis relance.")
                }
                searchTyped = true
                sleep(delay * 2)
                continue
            }

            // 1) Fenêtre de confirmation : SEULEMENT celle qu'on connaît.
            if (present(f, Role.TR_CONFIRM)) {
                val cancel = best(f, listOf(Role.TR_CANCEL))
                if (dry || selectedNow == 0) {
                    cancel?.let { tap(it, f) } ?: host.back()
                    sleep(delay)
                    if (dry) return "Essai à blanc : $selectedNow Pokémon sélectionnés, rien transféré.\n" +
                        "Vérifie la sélection, puis décoche « essai à blanc »."
                    continue
                }
                val ok = best(f, listOf(Role.TR_CONFIRM_BUTTON))
                    ?: throw Abort("Bouton de confirmation non reconnu : je ne transfère pas")
                host.status("Transfert de $selectedNow Pokémon…\n${summary()}")
                tap(ok, f)
                transferred += selectedNow
                selectedNow = 0
                pending = emptyList()
                attempts.clear()
                endReached = false
                sleep(delay * 3)
                if (transferred >= target) return "Objectif atteint : $transferred Pokémon transférés"
                continue
            }

            if (!present(f, Role.TR_LIST)) {
                idle++
                if (idle > 6) throw Abort(
                    "Écran non reconnu. Ouvre tes Pokémon, colle la recherche, trie par date de capture, puis relance.\n${summary()}",
                )
                sleep(poll); continue
            }
            idle = 0
            // La recherche a été tapée et relue par typeText() : pas de vérification à l'image (la
            // barre n'affiche que la fin d'une recherche longue).

            val evo = of(Role.TR_LIST).firstNotNullOfOrNull { t ->
                Matcher.find(f, t.pattern, threshold, t.colorTol)?.let { it.y + t.pattern.h }
            } ?: 0
            val labels = of(Role.TR_CELL).flatMap { t -> Matcher.findAll(f, t.pattern, threshold, t.colorTol) }
                .map { it.x to it.y }
            val cells = StorageGrid.cells(f, labels, evo)
            val selecting = present(f, Role.TR_SELECT)

            // Les cases touchées au tour précédent ne comptent que si elles apparaissent sélectionnées.
            for (p in pending) {
                if (cells.any { abs(it.x - p.x) < tol && abs(it.y - p.y) < tol && it.selected }) selectedNow++
            }
            pending = emptyList()

            if (!selecting) {
                if (selectedNow > 0) {
                    // Sortie inattendue du mode sélection : on repart de zéro.
                    selectedNow = 0
                }
                val first = cells.firstOrNull() ?: return "Plus aucun Pokémon à transférer ($transferred transférés)"
                host.status("Sélection…\n${summary()}")
                host.longPress(first.tapX / f.factor, first.tapY / f.factor)
                pending = listOf(first)
                settle(f)
                continue
            }

            val want = minOf(batchMax, target - transferred)
            if (selectedNow >= want || (endReached && selectedNow > 0)) {
                val b = best(f, listOf(Role.TR_BUTTON))
                if (b == null) {
                    // Peut-être encore en animation : quelques essais avant d'abandonner.
                    buttonMisses++
                    if (buttonMisses > 4) throw Abort("Bouton « TRANSFÉRER » non reconnu\n${summary()}")
                    sleep(delay); continue
                }
                buttonMisses = 0
                host.status("TRANSFÉRER ($selectedNow)…\n${summary()}")
                tap(b, f)
                settle(f)
                continue
            }
            if (endReached) return "Fin de la liste ($transferred transférés)"

            val todo = ArrayList<StorageCell>()
            for (c in cells) {
                if (c.selected) continue
                if (selectedNow + todo.size >= want) break
                val key = (c.x / tol) to (c.y / tol)
                val n = attempts[key] ?: 0
                // Une case qui ne se sélectionne pas après 2 essais est laissée de côté.
                if (n >= 2) continue
                attempts[key] = n + 1
                todo += c
            }
            if (todo.isNotEmpty()) {
                host.status("Sélection de ${todo.size} Pokémon…\n${summary()}")
                for (c in todo) {
                    host.tap(c.tapX / f.factor, c.tapY / f.factor)
                    sleep(120)
                }
                pending = todo
                settle(f)
                continue
            }

            // Défilement long : toutes les lignes déjà vues sortent par le haut, la suivante arrive juste
            // sous la ligne « AFFICHER LES ÉVOLUTIONS ».
            host.status("Défilement…\n${summary()}")
            // Une position par ligne de la grille (les « PC » d'une même ligne sont à la même hauteur).
            val rowYs = cells.map { it.y }.sorted().fold(ArrayList<Int>()) { acc, y ->
                if (acc.isEmpty() || y - acc.last() > 20) acc.add(y)
                acc
            }
            val pitch = pitchOf(rowYs, (f.w * 213f / 590f).toInt())
            val wanted = if (rowYs.isEmpty()) (f.h * 0.3f).toInt() else maxOf(rowYs.last() + pitch - evo - 6, pitch / 2)
            if (scrollContent(f, wanted, pitch) == 0) endReached = true
            attempts.clear()
        }
    }

    // ---------------------------------------------------------------- mode profil par profil

    private fun swipeMode(): String {
        val allowed = Role.entries.filter {
            it !in listOf(Role.PROFILE, Role.PROFILE_CLOSE, Role.LIST, Role.LIST_ROW, Role.LIST_GIFT, Role.LIST_SENT) &&
                when (it.category) {
                    Category.OPEN -> settings.doOpen
                    Category.SEND -> settings.doSend
                    Category.OTHER -> true
                }
        }
        if (of(*allowed.toTypedArray()).none { it.tpl.role.category != Category.OTHER }) {
            return "Apprends au moins un bouton d'ouverture ou d'envoi"
        }
        val profileRoles = allowed.filter { it != Role.CLOSE }

        var openDisabled = false
        var idle = 0
        var lost = 0
        var actions = 0
        var seenProfile = false
        val start = System.currentTimeMillis()

        while (true) {
            checkRunning()
            val f = stableFrame()
            checkPendingTap(f)
            val onProfile = present(f, Role.PROFILE)
            if (onProfile) {
                seenProfile = true; lost = 0
            }
            val roles = (if (onProfile) profileRoles else allowed)
                .filter { !(openDisabled && it.category == Category.OPEN) }
            val hit = if (actions < 30) best(f, roles) else null

            if (hit != null) {
                idle = 0
                when (hit.first.tpl.role) {
                    Role.STOP -> return "Écran « arrêt » reconnu\n${summary()}"
                    Role.LIMIT_OPEN -> openDisabled = true
                    Role.OPEN_CONFIRM -> opened++
                    Role.SEND_CONFIRM -> sent++
                    else -> {}
                }
                host.status("${label(hit)}\n${summary()}")
                tap(hit, f)
                actions++
                if (hit.first.tpl.role == Role.LIMIT_OPEN && !settings.doSend) return "Limite d'ouverture atteinte"
                sleep(delay)
                continue
            }

            if (!onProfile) {
                idle++
                if (!seenProfile) {
                    if (System.currentTimeMillis() - start > 8000) {
                        return "Profil d'ami introuvable. Ouvre le profil du premier ami, puis relance."
                    }
                    continue
                }
                if (idle < 3) continue
                lost++
                if (lost > 2) return "Écran inconnu, je m'arrête par sécurité"
                host.status("Écran inconnu → retour")
                host.back()
                idle = 0
                sleep(delay)
                continue
            }

            // Sur le profil et plus rien à faire : ami suivant.
            idle = 0
            if (visited + 1 >= settings.maxFriends) return "Nombre maximum d'amis atteint"
            host.status("Ami suivant…\n${summary()}")
            var moved = false
            for (attempt in 0 until 2) {
                host.swipeNextFriend(settings.swipeLeft)
                sleep(delay)
                if (Matcher.changedFraction(f, stableFrame()) > 0.02) {
                    moved = true; break
                }
            }
            if (!moved) return "Fin de la liste d'amis"
            visited++
            newFriend()
            actions = 0
        }
    }
}
