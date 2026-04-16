/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test

import kotlin.test.Test
import kotlin.test.assertEquals

class MessagesConventionsTestTest {

    @Test
    fun `already sorted properties remain unchanged`() {
        val content = """
            aaa=1
            bbb=2
            ccc=3
        """.trimIndent()

        assertEquals(content, sortPropertiesFileContent(content))
    }

    @Test
    fun `unsorted properties are sorted`() {
        val content = """
            ccc=3
            aaa=1
            bbb=2
        """.trimIndent()

        val expected = """
            aaa=1
            bbb=2
            ccc=3
        """.trimIndent()

        assertEquals(expected, sortPropertiesFileContent(content))
    }

    @Test
    fun `empty lines are preserved and attached to following property`() {
        val content = """
            aaa=1

            bbb=2
        """.trimIndent()

        assertEquals(content, sortPropertiesFileContent(content))
    }

    @Test
    fun `empty lines move with their property when sorted`() {
        val content = """
            bbb=2

            aaa=1
        """.trimIndent()

        // Empty line is attached to aaa=1, so it moves with it to the front
        val expected = """

            aaa=1
            bbb=2
        """.trimIndent()

        assertEquals(expected, sortPropertiesFileContent(content))
    }

    @Test
    fun `hash comments are preserved and attached to following property`() {
        val content = """
            # Comment for aaa
            aaa=1
            bbb=2
        """.trimIndent()

        assertEquals(content, sortPropertiesFileContent(content))
    }

    @Test
    fun `hash comments move with their property when sorted`() {
        val content = """
            # Comment for bbb
            bbb=2
            # Comment for aaa
            aaa=1
        """.trimIndent()

        val expected = """
            # Comment for aaa
            aaa=1
            # Comment for bbb
            bbb=2
        """.trimIndent()

        assertEquals(expected, sortPropertiesFileContent(content))
    }

    @Test
    fun `exclamation comments are preserved and attached to following property`() {
        val content = """
            ! Comment for aaa
            aaa=1
            bbb=2
        """.trimIndent()

        assertEquals(content, sortPropertiesFileContent(content))
    }

    @Test
    fun `exclamation comments move with their property when sorted`() {
        val content = """
            ! Comment for bbb
            bbb=2
            ! Comment for aaa
            aaa=1
        """.trimIndent()

        val expected = """
            ! Comment for aaa
            aaa=1
            ! Comment for bbb
            bbb=2
        """.trimIndent()

        assertEquals(expected, sortPropertiesFileContent(content))
    }

    @Test
    fun `multi-line properties with backslash continuation are handled correctly`() {
        val content = """
            aaa=line1\
            line2
            bbb=value
        """.trimIndent()

        assertEquals(content, sortPropertiesFileContent(content))
    }

    @Test
    fun `multi-line properties are sorted correctly`() {
        val content = """
            bbb=value
            aaa=line1\
            line2
        """.trimIndent()

        val expected = """
            aaa=line1\
            line2
            bbb=value
        """.trimIndent()

        assertEquals(expected, sortPropertiesFileContent(content))
    }

    @Test
    fun `trailing empty lines are preserved`() {
        val content = """
            aaa=1
            bbb=2

        """.trimIndent()

        assertEquals(content, sortPropertiesFileContent(content))
    }

    @Test
    fun `trailing comments are preserved`() {
        val content = """
            aaa=1
            bbb=2
            # trailing comment
        """.trimIndent()

        assertEquals(content, sortPropertiesFileContent(content))
    }

    @Test
    fun `multiple trailing empty lines and comments are preserved`() {
        val content = """
            aaa=1

            # trailing comment

        """.trimIndent()

        assertEquals(content, sortPropertiesFileContent(content))
    }

    @Test
    fun `complex case with empty lines comments and multi-line properties`() {
        val content = """
            # Header comment

            # Comment for ccc
            ccc=value3

            # Comment for aaa
            ! Another comment for aaa
            aaa=multi\
            line\
            value

            bbb=value2

            # Trailing comment
        """.trimIndent()

        // "# Header comment" and empty line are attached to ccc
        // "# Comment for aaa" and "! Another comment for aaa" are attached to aaa
        // Empty line before bbb is attached to bbb
        // When sorted: aaa (with its comments), bbb (with empty line), ccc (with header comment and its comment)
        val expected = """

            # Comment for aaa
            ! Another comment for aaa
            aaa=multi\
            line\
            value

            bbb=value2
            # Header comment

            # Comment for ccc
            ccc=value3

            # Trailing comment
        """.trimIndent()

        assertEquals(expected, sortPropertiesFileContent(content))
    }

    @Test
    fun `single property with no newlines`() {
        val content = "aaa=1"
        assertEquals(content, sortPropertiesFileContent(content))
    }

    @Test
    fun `empty content returns empty string`() {
        assertEquals("", sortPropertiesFileContent(""))
    }

    @Test
    fun `only comments and empty lines`() {
        val content = """
            # Just a comment

            ! Another comment
        """.trimIndent()

        assertEquals(content, sortPropertiesFileContent(content))
    }
}