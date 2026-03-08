package com.flipper.psadecrypt.storage

/**
 * Represents a file or directory on the Flipper's filesystem.
 */
data class FlipperFile(
    val name: String,
    val isDirectory: Boolean,
    val size: Long
)
