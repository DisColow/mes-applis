package com.discolow.cadeauxgo.bot

/**
 * Motifs intégrés, découpés dans des captures et vidéos réelles du jeu (écran 1280×2772,
 * vidéos réduites à 590 px de large) : plus besoin de calibrage. Images dans assets/builtin/.
 * [screenW] = largeur de l'écran d'origine ; [tapX]/[tapY] = point de clic relatif au motif,
 * en pixels de cet écran (null = centre).
 */
data class BuiltinDef(
    val file: String,
    val role: Role,
    val screenW: Int,
    val tapX: Int? = null,
    val tapY: Int? = null,
    /** Écart de couleur toléré ; 255 = pas de contrôle (repères dont le fond change). */
    val colorTol: Int = 40,
    /**
     * Zone où chercher (fractions de l'écran x0, y0, x1, y1), d'après la position du motif dans les
     * captures, avec une marge large. null = tout l'écran (éléments qui défilent, ex. cadeaux à choisir).
     */
    val area: FloatArray? = null,
)

/** Zone de recherche (fractions de l'écran). */
private fun zone(x0: Float, y0: Float, x1: Float, y1: Float) = floatArrayOf(x0, y0, x1, y1)

object Builtin {
    val all = listOf(
        // Repères de la liste d'amis : un seul suffit (l'encoche ou le panneau peuvent en cacher).
        BuiltinDef("list_amis.png", Role.LIST, 1280, colorTol = 255, area = zone(0.25f, 0f, 0.75f, 0.15f)),
        BuiltinDef("list_moi.png", Role.LIST, 1280, colorTol = 255, area = zone(0f, 0f, 0.4f, 0.15f)),
        BuiltinDef("list_social.png", Role.LIST, 1280, colorTol = 255, area = zone(0.6f, 0f, 1f, 0.15f)),
        BuiltinDef("list_giftbtn.png", Role.LIST, 1280, area = zone(0.6f, 0.8f, 1f, 1f)),
        BuiltinDef("list_recherche.png", Role.LIST, 590, area = zone(0.3f, 0.05f, 0.95f, 0.4f)),
        // Point de clic : le texte de la ligne (comme dans la vidéo), à hauteur de la photo de l'ami.
        BuiltinDef("list_heart.png", Role.LIST_ROW, 1280, tapX = 662, tapY = -112, area = zone(0f, 0.08f, 0.3f, 1f)),
        BuiltinDef("list_gift.png", Role.LIST_GIFT, 1280, area = zone(0.15f, 0.08f, 0.6f, 1f)),
        BuiltinDef("list_sent.png", Role.LIST_SENT, 1280, area = zone(0.05f, 0.08f, 0.4f, 1f)),
        BuiltinDef("open_ouvrir.png", Role.OPEN_CONFIRM, 590, area = zone(0f, 0.6f, 1f, 1f)),
        BuiltinDef("close_x.png", Role.CLOSE, 590, area = zone(0.25f, 0.75f, 0.75f, 1f)),
        BuiltinDef("close_x.png", Role.PROFILE_CLOSE, 590, area = zone(0.25f, 0.75f, 0.75f, 1f)),
        // Repères du profil : présents sur les deux mises en page (4 boutons avec « ÉCHANGE À
        // DISTANCE », ou 3 boutons avec « ÉCHANGER »). Un seul suffit.
        BuiltinDef("profile_mark.png", Role.PROFILE, 590, colorTol = 255, area = zone(0f, 0.4f, 1f, 1f)),
        BuiltinDef("profile_amitie.png", Role.PROFILE, 1280, colorTol = 255, area = zone(0.15f, 0.3f, 1f, 0.8f)),
        BuiltinDef("profile_activites.png", Role.PROFILE, 1280, colorTol = 255, area = zone(0f, 0.5f, 1f, 1f)),
        // Bouton CADEAU : petit (profil à 4 boutons) ou grand (profil à 3 boutons).
        BuiltinDef("profile_cadeau.png", Role.SEND_BUTTON, 590, area = zone(0f, 0.45f, 0.5f, 0.95f)),
        BuiltinDef("profile_cadeau_grand.png", Role.SEND_BUTTON, 1280, area = zone(0f, 0.45f, 0.5f, 0.95f)),
        BuiltinDef("gift_bow.png", Role.GIFT_ITEM, 590),
        BuiltinDef("send_envoyer.png", Role.SEND_CONFIRM, 590, area = zone(0f, 0.6f, 1f, 1f)),
        // Transfert de Pokémon (vidéo de 590 px de large).
        // Onglet « POKÉMON » ; clic : la barre de recherche, 130 px plus bas (vidéo de 590 px).
        BuiltinDef("tr_page.png", Role.TR_PAGE, 590, tapX = 61, tapY = 132, colorTol = 255, area = zone(0.2f, 0f, 0.8f, 0.15f)),
        BuiltinDef("tr_evolutions.png", Role.TR_LIST, 590, colorTol = 255, area = zone(0f, 0.1f, 1f, 0.35f)),
        BuiltinDef("tr_pc.png", Role.TR_CELL, 590, colorTol = 255, area = zone(0f, 0.15f, 1f, 1f)),
        BuiltinDef("tr_toutsel.png", Role.TR_SELECT, 590, area = zone(0.3f, 0f, 1f, 0.15f)),
        BuiltinDef("tr_btn.png", Role.TR_BUTTON, 590, area = zone(0f, 0.75f, 1f, 1f)),
        BuiltinDef("tr_confirm.png", Role.TR_CONFIRM, 590, area = zone(0f, 0.2f, 1f, 0.55f)),
        BuiltinDef("tr_confirm_btn.png", Role.TR_CONFIRM_BUTTON, 590, area = zone(0f, 0.4f, 1f, 0.75f)),
        BuiltinDef("tr_annuler.png", Role.TR_CANCEL, 590, area = zone(0f, 0.5f, 1f, 0.8f)),
    )
}
