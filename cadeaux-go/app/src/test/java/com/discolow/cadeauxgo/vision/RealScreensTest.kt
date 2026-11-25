package com.discolow.cadeauxgo.vision

import com.discolow.cadeauxgo.bot.FriendRow
import com.discolow.cadeauxgo.bot.FriendRows
import com.discolow.cadeauxgo.bot.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Vraies captures du jeu (vidéos d'ouverture « o_ » et d'envoi « s_ », capture de la liste),
 * motifs intégrés, seuil par défaut de l'appli. Beaucoup de motifs viennent d'une vidéo et sont
 * vérifiés sur l'autre (autre ami, autre fond).
 */
class RealScreensTest {
    private val threshold = 0.82

    private fun hits(screen: String, role: Role): List<Match> {
        val f = RealScreens.frame(screen)
        return RealScreens.builtins.filter { it.def.role == role }
            .flatMap { Matcher.findAll(f, it.pattern, threshold, it.def.colorTol) }
    }

    private fun assertSeen(role: Role, vararg screens: String) = screens.forEach {
        assertTrue("$role devrait être reconnu sur $it", hits(it, role).isNotEmpty())
    }

    private fun assertNotSeen(role: Role, vararg screens: String) = screens.forEach {
        assertTrue("$role ne devrait PAS être reconnu sur $it", hits(it, role).isEmpty())
    }

    private val lists = arrayOf("o_010", "s_001", "o_082", "shot_list", "shot_notch")
    private val profiles = arrayOf("o_050", "s_008", "s_034", "o_079", "p3_couleur", "p3_gris")
    private val others = arrayOf("o_020", "o_040", "o_042", "o_053", "s_012", "o_059", "s_019")

    @Test
    fun reperesDEcran() {
        assertSeen(Role.LIST, *lists)
        assertNotSeen(Role.LIST, *profiles, *others)
        assertSeen(Role.PROFILE, *profiles)
        assertNotSeen(Role.PROFILE, *lists, *others)
    }

    @Test
    fun boutonCadeauDuProfil() {
        // En couleur avant l'envoi (ami 2 : autre vidéo que le motif), gris après.
        assertSeen(Role.SEND_BUTTON, "o_050", "s_008")
        // Profil à 3 boutons (CADEAU / ÉCHANGER / COMBAT), icône plus grande.
        assertSeen(Role.SEND_BUTTON, "p3_couleur")
        // Profil à 3 boutons, fond bleu, cadeau déjà envoyé : CADEAU grisé à ignorer.
        assertNotSeen(Role.SEND_BUTTON, "p3_gris")
        assertNotSeen(Role.SEND_BUTTON, "s_034", "o_079")
    }

    @Test
    fun ouvrirEtEnvoyerNeSeConfondentPas() {
        assertSeen(Role.OPEN_CONFIRM, "o_020", "o_021")
        assertNotSeen(Role.OPEN_CONFIRM, "o_059", "s_019")
        assertSeen(Role.SEND_CONFIRM, "o_059", "s_019")
        assertNotSeen(Role.SEND_CONFIRM, "o_020", "o_021")
    }

    @Test
    fun premierCadeauAEnvoyer() {
        val bows = hits("s_012", Role.GIFT_ITEM)
        assertEquals(4, bows.size)
        val first = Matcher.firstInReadingOrder(bows, 30)
        assertNotNull(first)
        assertTrue("premier cadeau en haut à gauche : $first", first!!.x < 200 && first.y < 300)
    }

    @Test
    fun croixApresLesObjetsRecus() {
        assertSeen(Role.CLOSE, "o_042")
        // Sur les profils (y compris en cours d'apparition), la liste ne doit pas gêner leur fermeture.
        for (s in listOf("o_050", "s_006", "s_008", "s_034", "o_079", "p3_couleur", "p3_gris")) {
            assertTrue("pas de liste sur le profil $s", !nearly(s, Role.LIST))
        }
    }

    private fun rows(screen: String): List<FriendRow> {
        val f = RealScreens.frame(screen)
        fun boxes(role: Role) = RealScreens.builtins.filter { it.def.role == role }.flatMap { b ->
            Matcher.findAll(f, b.pattern, threshold, b.def.colorTol).map { FriendRows.Box(it.x, it.y, b.pattern.w, b.pattern.h) }
        }
        return FriendRows.analyze(boxes(Role.LIST_ROW), boxes(Role.LIST_GIFT), boxes(Role.LIST_SENT), f.h)
    }

    @Test
    fun listeAvantOuverture() {
        // ami 1, ami 2, ami 3, ami 4 : cadeau reçu, rien envoyé. ami 5 (sous les boutons) écarté.
        val r = rows("o_010")
        assertEquals(4, r.size)
        assertTrue(r.all { it.hasGift && !it.alreadySent })
    }

    @Test
    fun listeApresLaPremiereVisite() {
        // ami 1 : cadeau ouvert + envoyé (flèche). ami 2 : cadeau ouvert, pas encore envoyé.
        val r = rows("s_001")
        assertEquals(4, r.size)
        assertEquals(listOf(false, false, true, true), r.map { it.hasGift })
        assertEquals(listOf(true, false, false, false), r.map { it.alreadySent })
        assertEquals(listOf(false, true, true, true), r.map { it.needsVisit(open = true, send = true) })
        assertEquals(listOf(false, false, true, true), r.map { it.needsVisit(open = true, send = false) })
    }

    @Test
    fun captureDeLaListe() {
        // ami 4, ami 5, ami 6 : cadeau ; ami 7, ami 8 : flèche. ami 9 écartée.
        val r = rows("shot_list")
        assertEquals(5, r.size)
        assertEquals(listOf(true, true, true, false, false), r.map { it.hasGift })
        assertEquals(listOf(false, false, false, true, true), r.map { it.alreadySent })
    }

    @Test
    fun pointDeClicSurLaLigneDeLAmi() {
        // Capture 1280×2772 : la ligne de ami 5 s'étend de y≈620 à y≈960, son texte de x≈370 à x≈1190.
        val f = RealScreens.frame("shot_list")
        val heart = RealScreens.builtins.first { it.def.role == Role.LIST_ROW }
        val vaker = Matcher.findAll(f, heart.pattern, threshold, heart.def.colorTol)
            .filter { abs(it.y / f.factor - 846) < 30 }.minByOrNull { it.x }
        assertNotNull(vaker)
        val s = f.srcW.toFloat() / heart.def.screenW
        val x = vaker!!.x / f.factor + heart.def.tapX!! * s
        val y = vaker.y / f.factor + heart.def.tapY!! * s
        assertTrue("x=$x", x in 370f..1190f)
        assertTrue("y=$y", y in 620f..960f)
    }

    private fun nearly(screen: String, role: Role): Boolean {
        val f = RealScreens.frame(screen)
        return RealScreens.builtins.filter { it.def.role == role }
            .any { Matcher.find(f, it.pattern, 0.6, it.def.colorTol) != null }
    }

    @Test
    fun gardeFouDeLaCroix() {
        // La croix ronde du bas ferme la liste d'amis : la liste doit être repérée (même en transition)
        // sur toutes les captures de liste, pour que la croix n'y soit jamais touchée…
        for (s in listOf("o_008", "o_009", "o_010", "o_016", "o_080", "o_082", "s_001", "s_005", "shot_list")) {
            assertTrue("liste repérée sur $s", nearly(s, Role.LIST))
        }
        // … mais pas sur l'écran des objets reçus, où la croix doit rester utilisable.
        for (s in listOf("o_040", "o_042")) {
            assertTrue("pas de liste sur $s", !nearly(s, Role.LIST))
            assertTrue("pas de profil sur $s", !nearly(s, Role.PROFILE))
        }
        assertSeen(Role.CLOSE, "o_042")
        // Sur les profils (y compris en cours d'apparition), la liste ne doit pas gêner leur fermeture.
        for (s in listOf("o_050", "s_006", "s_008", "s_034", "o_079", "p3_couleur", "p3_gris")) {
            assertTrue("pas de liste sur le profil $s", !nearly(s, Role.LIST))
        }
    }

    @Test
    fun listeAvecEncocheEtPanneau() {
        // « AMIS » caché par la bulle de l'encoche, ligne d'ami 8 cachée par le panneau de l'appli :
        // la liste reste reconnue (MOI, SOCIAL, RECHERCHE, bouton du bas) et les lignes visibles analysées.
        assertSeen(Role.LIST, "shot_notch")
        val r = rows("shot_notch")
        // ami 6 (à qui envoyer), ami 7 (flèche), ami 1 (flèche). ami 9 sous les boutons.
        assertEquals(3, r.size)
        assertEquals(listOf(false, true, true), r.map { it.alreadySent })
        assertEquals(listOf(true, false, false), r.map { it.needsVisit(open = true, send = true) })
    }
}
