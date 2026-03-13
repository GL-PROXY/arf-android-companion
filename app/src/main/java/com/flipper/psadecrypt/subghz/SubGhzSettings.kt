package com.flipper.psadecrypt.subghz

data class SubGhzSettings(
    var addStandardFrequencies: Boolean = true,
    var defaultFrequency: Long? = null,
    val frequencies: MutableList<Long> = mutableListOf(),
    val hopperFrequencies: MutableList<Long> = mutableListOf(),
    val customPresets: MutableList<CustomPreset> = mutableListOf(),
    val hoppingPresets: MutableList<String> = mutableListOf()
)

data class CustomPreset(
    var name: String,
    var module: String = "CC1101",
    var data: String
)

object SubGhzSettingsParser {

    private const val HEADER_FILETYPE = "Filetype: Flipper SubGhz Setting File"
    private const val HEADER_VERSION = "Version: 1"

    /**
     * Parse the content of a setting_user file into a [SubGhzSettings] object.
     * Returns default settings if the header is invalid.
     */
    fun parse(text: String): SubGhzSettings {
        val lines = text.lines()
        val settings = SubGhzSettings()

        // Validate header
        val nonEmpty = lines.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (nonEmpty.size < 2 ||
            nonEmpty[0].trim() != HEADER_FILETYPE ||
            nonEmpty[1].trim() != HEADER_VERSION
        ) {
            return settings
        }

        var pendingPresetName: String? = null
        var pendingPresetModule: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (trimmed == HEADER_FILETYPE || trimmed == HEADER_VERSION) continue

            val colonIdx = trimmed.indexOf(':')
            if (colonIdx < 0) continue

            val key = trimmed.substring(0, colonIdx).trim()
            val value = trimmed.substring(colonIdx + 1).trim()

            when (key) {
                "Add_standard_frequencies" -> {
                    settings.addStandardFrequencies = value.equals("true", ignoreCase = true)
                }
                "Default_frequency" -> {
                    value.toLongOrNull()?.let { settings.defaultFrequency = it }
                }
                "Frequency" -> {
                    value.toLongOrNull()?.let { settings.frequencies.add(it) }
                }
                "Hopper_frequency" -> {
                    value.toLongOrNull()?.let { settings.hopperFrequencies.add(it) }
                }
                "Custom_preset_name" -> {
                    // Flush previous preset if complete
                    flushPreset(settings, pendingPresetName, pendingPresetModule, null)
                    pendingPresetName = value
                    pendingPresetModule = null
                }
                "Custom_preset_module" -> {
                    pendingPresetModule = value
                }
                "Custom_preset_data" -> {
                    if (pendingPresetName != null) {
                        settings.customPresets.add(
                            CustomPreset(
                                name = pendingPresetName,
                                module = pendingPresetModule ?: "CC1101",
                                data = value
                            )
                        )
                        pendingPresetName = null
                        pendingPresetModule = null
                    }
                }
                "Hopping_Preset" -> {
                    if (value.isNotBlank()) {
                        settings.hoppingPresets.add(value)
                    }
                }
            }
        }

        return settings
    }

    private fun flushPreset(
        settings: SubGhzSettings,
        name: String?,
        module: String?,
        data: String?
    ) {
        // Only flush if we had a name+data pair waiting (incomplete blocks are skipped)
    }

    /**
     * Serialize a [SubGhzSettings] object back to the setting_user file format.
     */
    fun serialize(settings: SubGhzSettings): String {
        val sb = StringBuilder()
        sb.appendLine(HEADER_FILETYPE)
        sb.appendLine(HEADER_VERSION)
        sb.appendLine()

        sb.appendLine("Add_standard_frequencies: ${settings.addStandardFrequencies}")
        sb.appendLine()

        settings.defaultFrequency?.let {
            sb.appendLine("Default_frequency: $it")
            sb.appendLine()
        }

        for (freq in settings.frequencies) {
            sb.appendLine("Frequency: $freq")
        }
        if (settings.frequencies.isNotEmpty()) sb.appendLine()

        for (freq in settings.hopperFrequencies) {
            sb.appendLine("Hopper_frequency: $freq")
        }
        if (settings.hopperFrequencies.isNotEmpty()) sb.appendLine()

        for (preset in settings.customPresets) {
            sb.appendLine("Custom_preset_name: ${preset.name}")
            sb.appendLine("Custom_preset_module: ${preset.module}")
            sb.appendLine("Custom_preset_data: ${preset.data}")
            sb.appendLine()
        }

        for (hp in settings.hoppingPresets) {
            sb.appendLine("Hopping_Preset: $hp")
        }
        if (settings.hoppingPresets.isNotEmpty()) sb.appendLine()

        return sb.toString()
    }

    /**
     * Format frequency for display: "433.920 MHz"
     */
    fun formatFrequency(hz: Long): String {
        val mhz = hz / 1_000_000.0
        return "%.3f MHz".format(mhz)
    }

    /**
     * Parse CC1101 preset hex data string into register pairs and PA table.
     * Format: "REG1 VAL1 REG2 VAL2 ... 00 00 PA0 PA1 PA2 PA3 PA4 PA5 PA6 PA7"
     *
     * @return (registerPairs, paTableBytes) where each pair is (address, value) as uppercase hex strings
     */
    fun parsePresetData(hexString: String): Pair<List<Pair<String, String>>, List<String>> {
        val bytes = hexString.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val registers = mutableListOf<Pair<String, String>>()
        val paTable = mutableListOf<String>()

        // Find the 00 00 terminator
        var terminatorIndex = -1
        var i = 0
        while (i < bytes.size - 1) {
            if (bytes[i].equals("00", ignoreCase = true) && bytes[i + 1].equals("00", ignoreCase = true)) {
                terminatorIndex = i
                break
            }
            i += 2
        }

        if (terminatorIndex < 0) {
            // No terminator found — treat all as register pairs, empty PA
            i = 0
            while (i + 1 < bytes.size) {
                registers.add(bytes[i].uppercase() to bytes[i + 1].uppercase())
                i += 2
            }
            return registers to List(8) { "00" }
        }

        // Parse register pairs before terminator
        i = 0
        while (i < terminatorIndex) {
            if (i + 1 < bytes.size) {
                registers.add(bytes[i].uppercase() to bytes[i + 1].uppercase())
            }
            i += 2
        }

        // Parse PA table (8 bytes after terminator)
        val paStart = terminatorIndex + 2
        for (j in 0 until 8) {
            if (paStart + j < bytes.size) {
                paTable.add(bytes[paStart + j].uppercase())
            } else {
                paTable.add("00")
            }
        }

        return registers to paTable
    }

    /**
     * Serialize register pairs and PA table back to hex data string.
     */
    fun serializePresetData(registers: List<Pair<String, String>>, paTable: List<String>): String {
        val parts = mutableListOf<String>()
        for ((addr, value) in registers) {
            parts.add(addr.uppercase())
            parts.add(value.uppercase())
        }
        parts.add("00")
        parts.add("00")
        for (i in 0 until 8) {
            parts.add(if (i < paTable.size) paTable[i].uppercase() else "00")
        }
        return parts.joinToString(" ")
    }
}
