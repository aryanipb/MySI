package com.mysi.agent.models

import java.io.File

/** Shared test data: the shipped catalogue and realistic phones. */
object ModelFixtures {
    const val GIB = 1L shl 30

    /** The catalogue built into the app (tests run with app/ as the working directory). */
    val builtInJson: String by lazy { File("src/main/assets/models.json").readText() }

    val builtIn: Catalog by lazy {
        when (val p = CatalogParser.parse(builtInJson)) {
            is CatalogParser.Result.Valid -> p.catalog
            is CatalogParser.Result.Invalid -> error("Built-in catalogue invalid: ${p.problem}")
        }
    }

    fun entry(id: String) = builtIn.byId(id) ?: error("no $id")
}
