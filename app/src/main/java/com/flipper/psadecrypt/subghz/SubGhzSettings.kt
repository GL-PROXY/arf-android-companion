package com.flipper.psadecrypt.subghz

data class SubGhzSettings(
    var addStandardFrequencies: Boolean = true,
    var defaultFrequency: Long? = null,
    val frequencies: MutableList<Long> = mutableListOf(),
    val hopperFrequencies: MutableList<Long> = mutableListOf(),
    val customPresets: MutableList<CustomPreset> = mutableListOf()
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

        return sb.toString()
    }

    /**
     * Format a frequency in Hz to a human-readable MHz string.
     */
    fun formatFrequencyMHz(hz: Long): String {
        val mhz = hz / 1_000_000.0
        return if (mhz == mhz.toLong().toDouble()) {
            "${mhz.toLong()} MHz"
        } else {
            "%.3f MHz".format(mhz).trimEnd('0').trimEnd('.') + " MHz"
        }
    }

    /**
     * Format frequency for display: "433.920 MHz"
     */
    fun formatFrequency(hz: Long): String {
        val mhz = hz / 1_000_000.0
        return "%.3f MHz".format(mhz)
    }
}
