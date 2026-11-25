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
)

object Builtin {
    val all = listOf(
        // Repères de la liste d'amis : un seul suffit (l'encoche ou le panneau peuvent en cacher).
        BuiltinDef("list_amis.png", Role.LIST, 1280, colorTol = 255),
        BuiltinDef("list_moi.png", Role.LIST, 1280, colorTol = 255),
        BuiltinDef("list_social.png", Role.LIST, 1280, colorTol = 255),
        BuiltinDef("list_giftbtn.png", Role.LIST, 1280),
        BuiltinDef("list_recherche.png", Role.LIST, 590),
        // Point de clic : le texte de la ligne (comme dans la vidéo), à hauteur de la photo de l'ami.
        BuiltinDef("list_heart.png", Role.LIST_ROW, 1280, tapX = 662, tapY = -112),
        BuiltinDef("list_gift.png", Role.LIST_GIFT, 1280),
        BuiltinDef("list_sent.png", Role.LIST_SENT, 1280),
        BuiltinDef("open_ouvrir.png", Role.OPEN_CONFIRM, 590),
        BuiltinDef("close_x.png", Role.CLOSE, 590),
        BuiltinDef("close_x.png", Role.PROFILE_CLOSE, 590),
        // Repères du profil : présents sur les deux mises en page (4 boutons avec « ÉCHANGE À
        // DISTANCE », ou 3 boutons avec « ÉCHANGER »). Un seul suffit.
        BuiltinDef("profile_mark.png", Role.PROFILE, 590, colorTol = 255),
        BuiltinDef("profile_amitie.png", Role.PROFILE, 1280, colorTol = 255),
        BuiltinDef("profile_activites.png", Role.PROFILE, 1280, colorTol = 255),
        // Bouton CADEAU : petit (profil à 4 boutons) ou grand (profil à 3 boutons).
        BuiltinDef("profile_cadeau.png", Role.SEND_BUTTON, 590),
        BuiltinDef("profile_cadeau_grand.png", Role.SEND_BUTTON, 1280),
        BuiltinDef("gift_bow.png", Role.GIFT_ITEM, 590),
        BuiltinDef("send_envoyer.png", Role.SEND_CONFIRM, 590),
        // Transfert de Pokémon (vidéo de 590 px de large).
        // Onglet « POKÉMON » ; clic : la barre de recherche, 130 px plus bas (vidéo de 590 px).
        BuiltinDef("tr_page.png", Role.TR_PAGE, 590, tapX = 61, tapY = 132, colorTol = 255),
        BuiltinDef("tr_evolutions.png", Role.TR_LIST, 590, colorTol = 255),
        BuiltinDef("tr_pc.png", Role.TR_CELL, 590, colorTol = 255),
        BuiltinDef("tr_toutsel.png", Role.TR_SELECT, 590),
        BuiltinDef("tr_btn.png", Role.TR_BUTTON, 590),
        BuiltinDef("tr_confirm.png", Role.TR_CONFIRM, 590),
        BuiltinDef("tr_confirm_btn.png", Role.TR_CONFIRM_BUTTON, 590),
        BuiltinDef("tr_annuler.png", Role.TR_CANCEL, 590),
    )
}
