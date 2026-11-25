package com.discolow.cadeauxgo.bot

/**
 * Recherche du jeu pour les Pokémon à transférer (code pur, testé).
 * Dans la recherche de Pokémon GO : « , » = OU, « & » = ET, « ! » = NON, « pc-1500 » = 1500 PC max.
 */
object TransferSearch {
    /** Exclusions toujours appliquées : Pokémon spéciaux. */
    val SPECIAL = listOf(
        "chromatique", "légendaire", "fabuleux", "ultra-chimère", "costume", "chanceux",
        // « favori » et « favoris » : selon la version du jeu. Un mot inconnu du jeu est lu comme un nom
        // de Pokémon, donc la variante en trop n'exclut rien d'autre.
        "obscur", "purifié", "favori", "favoris", "dynamax", "gigamax",
        // « # » = Pokémon qui ont au moins un mot-clé (tag) : « !# » les garde tous.
        "#",
    )

    /** Espèces rares gardées par défaut (modifiable dans l'appli). */
    const val DEFAULT_KEEP =
        "leveinard, ronflex, minidraco, embrylex, terhal, draby, griknot, ptéra, lokhlass, " +
            "kangourex, tauros, axoloto"

    fun species(list: String): List<String> =
        list.split(',', ';', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    fun build(maxCp: Int, maxHp: Int, keep: String): String =
        (listOf("0*,1*,2*") + SPECIAL.map { "!$it" } + listOf("pc-$maxCp", "pv-$maxHp") + species(keep).map { "!$it" })
            .joinToString("&")
}
