package eu.kanade.tachiyomi.lib.unpacker

/*
 * Copyright (C) The Tachiyomi Open Source Project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

/**
 * A helper class to extract substrings efficiently.
 *
 * Note that all methods move [startIndex] over the ending delimiter.
 */
class SubstringExtractor(private val text: String) {
    private var startIndex = 0

    fun skipOver(str: String) {
        val index = text.indexOf(str, startIndex)
        if (index == -1) return
        startIndex = index + str.length
    }

    fun substringBefore(str: String): String {
        val index = text.indexOf(str, startIndex)
        if (index == -1) return ""
        val result = text.substring(startIndex, index)
        startIndex = index + str.length
        return result
    }

    fun substringBetween(left: String, right: String): String {
        val index = text.indexOf(left, startIndex)
        if (index == -1) return ""
        val leftIndex = index + left.length
        val rightIndex = text.indexOf(right, leftIndex)
        if (rightIndex == -1) return ""
        startIndex = rightIndex + right.length
        return text.substring(leftIndex, rightIndex)
    }
}
