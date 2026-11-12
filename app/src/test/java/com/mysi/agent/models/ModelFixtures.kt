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

    /**
     * A phone as Android reports it. [totalGib] is what the kernel manages, a little below the
     * advertised [advertisedGb] (for example 7.4 GiB on an "8 GB" phone).
     */
    fun phone(
        advertisedGb: Int,
        totalGib: Double,
        freeStorage: Long = 64_000_000_000L,
        available: Long = (totalGib * GIB * 0.45).toLong(),
        unmetered: Boolean = true,
        online: Boolean = true,
        lowRam: Boolean = false,
        lowMemoryNow: Boolean = false,
        socManufacturer: String = "Qualcomm",
        socModel: String = "SM8650",
        abis: List<String> = listOf("arm64-v8a"),
    ) = DeviceProfile(
        totalRamBytes = (totalGib * GIB).toLong(),
        advertisedRamBytes = advertisedGb * GIB,
        availableRamBytes = available,
        lowMemoryNow = lowMemoryNow,
        lowRamDevice = lowRam,
        freeStorageBytes = freeStorage,
        socManufacturer = socManufacturer,
        socModel = socModel,
        hardware = "qcom",
        abis = abis,
        sdkInt = 35,
        unmetered = unmetered,
        online = online,
    )

    // Typical totalMem readings for each advertised size.
    val phone4 get() = phone(4, 3.6)
    val phone6 get() = phone(6, 5.5)
    val phone8 get() = phone(8, 7.4)
    val phone12 get() = phone(12, 11.2)
    val phone16 get() = phone(16, 14.9)
}
