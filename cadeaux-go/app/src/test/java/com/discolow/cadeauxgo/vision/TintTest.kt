package com.discolow.cadeauxgo.vision

import com.discolow.cadeauxgo.bot.FriendRow
import com.discolow.cadeauxgo.bot.FriendRows
import com.discolow.cadeauxgo.bot.Role
import com.discolow.cadeauxgo.bot.StorageGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Écran teinté par un filtre (lumière bleue, mode nuit…) : vraies captures teintées (« b_ ») et
 * captures normales passées à travers le même filtre. Les contrôles de couleur doivent tenir.
 */
class TintTest {
    private val threshold = 0.82

    private fun hits(f: Frame, role: Role) = RealScreens.builtins.filter { it.def.role == role }
        .flatMap { Matcher.findAll(f, it.pattern, threshold, it.def.colorTol) }

    private fun rows(f: Frame): List<FriendRow> {
        fun boxes(role: Role) = RealScreens.builtins.filter { it.def.role == role }.flatMap { b ->
            Matcher.findAll(f, b.pattern, threshold, b.def.colorTol).map { FriendRows.Box(it.x, it.y, b.pattern.w, b.pattern.h) }
        }
        return FriendRows.analyze(boxes(Role.LIST_ROW), boxes(Role.LIST_GIFT), boxes(Role.LIST_SENT), f.h)
    }

    @Test
    fun ecransNormauxNonCorriges() {
        for (n in listOf("o_010", "o_020", "o_040", "o_042", "o_050", "o_053", "o_059", "s_008", "s_034", "shot_list", "p3_gris", "t_014", "t_030", "t_073")) {
            assertNull("pas de teinte sur $n", RealScreens.frame(n).tint)
        }
    }

    @Test
    fun teinteDetectee() {
        for (n in listOf("b_008", "b_025", "b_045")) assertNotNull("teinte sur $n", RealScreens.frame(n).tint)
        assertNotNull(RealScreens.frameTinted("o_010").tint)
    }

    @Test
    fun vraieVideoTeintee() {
        // Liste : les 4 lignes visibles ont la flèche « déjà envoyé » (rien à faire).
        val list = rows(RealScreens.frame("b_008"))
        assertTrue("lignes : ${list.size}", list.size >= 3)
        assertTrue(list.all { it.alreadySent })
        assertTrue(list.none { it.needsVisit(open = true, send = true) })
        // Profils (4 et 3 boutons) : repère et croix reconnus, CADEAU grisé ignoré.
        for (n in listOf("b_025", "b_045")) {
            val f = RealScreens.frame(n)
            assertTrue("profil $n", hits(f, Role.PROFILE).isNotEmpty())
            assertTrue("croix $n", hits(f, Role.PROFILE_CLOSE).isNotEmpty())
            assertTrue("CADEAU grisé $n", hits(f, Role.SEND_BUTTON).isEmpty())
        }
    }

    @Test
    fun cadeauxAvecFiltre() {
        val r = rows(RealScreens.frameTinted("shot_list"))
        assertEquals("cadeaux", listOf(true, true, true, false, false), r.map { it.hasGift })
        assertEquals("flèches", listOf(false, false, false, true, true), r.map { it.alreadySent })
        val r2 = rows(RealScreens.frameTinted("s_001"))
        assertEquals("flèches s_001", listOf(true, false, false, false), r2.map { it.alreadySent })
        assertTrue("SEND_BUTTON isNotEmpty s_008", hits(RealScreens.frameTinted("s_008"), Role.SEND_BUTTON).isNotEmpty())
        assertTrue("SEND_BUTTON isNotEmpty p3_couleur", hits(RealScreens.frameTinted("p3_couleur"), Role.SEND_BUTTON).isNotEmpty())
        assertTrue("SEND_BUTTON isEmpty s_034", hits(RealScreens.frameTinted("s_034"), Role.SEND_BUTTON).isEmpty())
        assertTrue("SEND_BUTTON isEmpty p3_gris", hits(RealScreens.frameTinted("p3_gris"), Role.SEND_BUTTON).isEmpty())
        assertTrue("PROFILE_CLOSE isNotEmpty o_050", hits(RealScreens.frameTinted("o_050"), Role.PROFILE_CLOSE).isNotEmpty())
        assertTrue("OPEN_CONFIRM isNotEmpty o_020", hits(RealScreens.frameTinted("o_020"), Role.OPEN_CONFIRM).isNotEmpty())
        assertTrue("SEND_CONFIRM isNotEmpty s_019", hits(RealScreens.frameTinted("s_019"), Role.SEND_CONFIRM).isNotEmpty())
        assertTrue("GIFT_ITEM isNotEmpty s_012", hits(RealScreens.frameTinted("s_012"), Role.GIFT_ITEM).isNotEmpty())
    }

    @Test
    fun transfertAvecFiltre() {
        assertTrue("TR_CONFIRM_BUTTON isNotEmpty t_073", hits(RealScreens.frameTinted("t_073"), Role.TR_CONFIRM_BUTTON).isNotEmpty())
        assertTrue("TR_CONFIRM_BUTTON isEmpty t_030", hits(RealScreens.frameTinted("t_030"), Role.TR_CONFIRM_BUTTON).isEmpty())
        assertTrue("TR_BUTTON isNotEmpty t_030", hits(RealScreens.frameTinted("t_030"), Role.TR_BUTTON).isNotEmpty())
        assertTrue("TR_SELECT isNotEmpty t_030", hits(RealScreens.frameTinted("t_030"), Role.TR_SELECT).isNotEmpty())
        val f = RealScreens.frameTinted("t_030")
        val evo = RealScreens.builtins.first { it.def.role == Role.TR_LIST }
        val e = Matcher.find(f, evo.pattern, threshold, evo.def.colorTol)!!
        val pc = RealScreens.builtins.first { it.def.role == Role.TR_CELL }
        val labels = Matcher.findAll(f, pc.pattern, threshold, pc.def.colorTol).map { it.x to it.y }
        val cells = StorageGrid.cells(f, labels, e.y + evo.pattern.h)
        assertEquals(listOf(true, true, true, true, true, true, false, false, false), cells.map { it.selected })
    }
}
