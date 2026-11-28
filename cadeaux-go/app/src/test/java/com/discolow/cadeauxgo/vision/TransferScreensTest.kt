package com.discolow.cadeauxgo.vision

import com.discolow.cadeauxgo.bot.Role
import com.discolow.cadeauxgo.bot.StorageCell
import com.discolow.cadeauxgo.bot.StorageGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vraies captures de la vidéo de transfert (« t_ ») et de l'appli (cadeaux). */
class TransferScreensTest {
    private val threshold = 0.82

    private fun hits(screen: String, role: Role): List<Match> {
        val f = RealScreens.frame(screen)
        return RealScreens.builtins.filter { it.def.role == role }
            .flatMap { Matcher.findAll(f, it.pattern, threshold, it.def.colorTol) }
    }

    private fun seen(role: Role, vararg s: String) = s.forEach { assertTrue("$role sur $it", hits(it, role).isNotEmpty()) }
    private fun notSeen(role: Role, vararg s: String) = s.forEach { assertTrue("$role PAS sur $it", hits(it, role).isEmpty()) }

    private val giftScreens = arrayOf("o_010", "o_020", "o_050", "o_053", "o_059", "shot_list", "p3_couleur")

    @Test
    fun ecransDuTransfert() {
        // Page POKÉMON (avec ou sans recherche), pas en mode sélection ni ailleurs.
        seen(Role.TR_PAGE, "t_005", "t_014")
        // Bulle de l'encoche (minuteur) juste au-dessus de l'onglet « POKÉMON ».
        seen(Role.TR_PAGE, "r_001", "r_010", "r_025")
        notSeen(Role.TR_PAGE, "t_030", "t_073", *giftScreens)
        seen(Role.TR_LIST, "t_014", "t_016", "t_030", "t_040")
        // Stockage sans recherche (« Chercher » vide) : pas de ligne « AFFICHER LES ÉVOLUTIONS ».
        notSeen(Role.TR_LIST, "t_005", "t_073", *giftScreens)
        seen(Role.TR_SELECT, "t_016", "t_030", "t_040")
        notSeen(Role.TR_SELECT, "t_014", "t_073", *giftScreens)
        seen(Role.TR_BUTTON, "t_016", "t_030", "t_040")
        notSeen(Role.TR_BUTTON, "t_014", *giftScreens)
        seen(Role.TR_CONFIRM, "t_073")
        notSeen(Role.TR_CONFIRM, "t_014", "t_030", *giftScreens)
        seen(Role.TR_CONFIRM_BUTTON, "t_073")
        seen(Role.TR_CANCEL, "t_073")
        // Le bouton vert de confirmation ne doit pas être confondu avec « TRANSFÉRER (n) » du mode sélection.
        notSeen(Role.TR_CONFIRM_BUTTON, "t_016", "t_030", "t_040", *giftScreens)
    }

    private fun cells(screen: String): List<StorageCell> {
        val f = RealScreens.frame(screen)
        val evo = RealScreens.builtins.first { it.def.role == Role.TR_LIST }
        val e = Matcher.find(f, evo.pattern, threshold, evo.def.colorTol)!!
        val pc = RealScreens.builtins.first { it.def.role == Role.TR_CELL }
        val labels = Matcher.findAll(f, pc.pattern, threshold, pc.def.colorTol).map { it.x to it.y }
        return StorageGrid.cells(f, labels, e.y + evo.pattern.h)
    }

    @Test
    fun grilleSansSelection() {
        // Munna ×3, Mélofée/Ponchiot/Kicklee, Métamorph/Poussifeu/Évoli : 3 lignes entières.
        val c = cells("t_014")
        assertEquals(9, c.size)
        assertTrue(c.none { it.selected })
    }

    @Test
    fun grilleAvecSelection() {
        // Racaillou/Hexagel/Kraknoix et Nosferalto/Makuhita/Goélise sélectionnés, Tylton/Embrylex/Amonita non.
        val c = cells("t_030")
        assertEquals(9, c.size)
        assertEquals(listOf(true, true, true, true, true, true, false, false, false), c.map { it.selected })
    }

    @Test
    fun premiereSelection() {
        // Juste après l'appui long : seul le premier Munna est sélectionné.
        val c = cells("t_017")
        assertTrue("au moins 6 cases : ${c.size}", c.size >= 6)
        assertEquals(1, c.count { it.selected })
        assertTrue(c.first().selected)
    }

    @Test
    fun clicSurLaBarreDeRecherche() {
        // Vidéo de 590 px : la barre « Chercher » va de x≈25 à 565 et de y≈165 à 220. Les deux onglets
        // (« POKÉMON » et « ŒUFS ») mènent au même endroit, avec ou sans la bulle de l'encoche.
        for (n in listOf("t_005", "r_010")) {
            val f = RealScreens.frame(n)
            for (page in RealScreens.builtins.filter { it.def.role == Role.TR_PAGE }) {
                val m = Matcher.find(f, page.pattern, threshold, page.def.colorTol)
                org.junit.Assert.assertNotNull("${page.def.file} sur $n", m)
                val s = f.srcW.toFloat() / page.def.screenW
                val x = m!!.x / f.factor + page.def.tapX!! * s
                val y = m.y / f.factor + page.def.tapY!! * s
                assertTrue("${page.def.file} $n x=$x", x in 25f..565f)
                assertTrue("${page.def.file} $n y=$y", y in 165f..220f)
            }
        }
    }

    private fun seenOn(f: Frame, role: Role) = RealScreens.builtins.filter { it.def.role == role }
        .any { Matcher.find(f, it.pattern, threshold, it.def.colorTol) != null }

    @Test
    fun panneauEnBasCacheLeBoutonTransferer() {
        // Panneau réduit en bas à gauche (38 % de large, ~6 % de haut) : il masque « TRANSFÉRER (n) ».
        val f = RealScreens.frameWithPanel("t_030", 0f, 0.92f, 0.38f, 1f)
        assertTrue(!seenOn(f, Role.TR_BUTTON))
    }

    @Test
    fun panneauEnHautNeCacheRienDUtile() {
        // Panneau réduit en haut à gauche, sous la barre d'état : tout le parcours reste reconnu.
        val box = floatArrayOf(0f, 0.03f, 0.38f, 0.10f)
        fun f(n: String) = RealScreens.frameWithPanel(n, box[0], box[1], box[2], box[3])
        assertTrue(seenOn(f("t_005"), Role.TR_PAGE))
        assertTrue(seenOn(f("t_014"), Role.TR_LIST))
        for (n in listOf("t_017", "t_030", "t_040")) {
            assertTrue("sélection $n", seenOn(f(n), Role.TR_SELECT))
            assertTrue("bouton $n", seenOn(f(n), Role.TR_BUTTON))
            assertTrue("liste $n", seenOn(f(n), Role.TR_LIST))
        }
        assertTrue(seenOn(f("t_073"), Role.TR_CONFIRM))
        assertTrue(seenOn(f("t_073"), Role.TR_CONFIRM_BUTTON))
        assertTrue(seenOn(f("t_073"), Role.TR_CANCEL))
    }
}
