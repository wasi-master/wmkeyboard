package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Test

/** `{clip:…}` and `{selection:…}` modifiers (competitor gap list item 8). */
class SnippetModifierTest {

    private fun expand(text: String, clip: String, selection: String? = null) =
        SnippetStore.expand(
            text,
            context = SnippetStore.Companion.Context(clipboard = clip, selection = selection),
        )

    @Test fun cleanStripsCitationMarks() {
        assertEquals(
            "Paris is the capital of France. It has 2 million people.",
            expand("{clip:clean}", "Paris is the capital of France.[1] It has 2 million people[citation needed].[a]"),
        )
    }

    @Test fun singlelineJoinsLines() {
        assertEquals("one two three", expand("{clip:singleline}", "one\n  two\n\nthree  "))
    }

    @Test fun chainsApplyLeftToRight() {
        assertEquals("HELLO WORLD", expand("{clip:singleline:upper}", "hello\nworld"))
        assertEquals("Hello World", expand("{clip:lower:title}", "hELLO wORLD"))
    }

    @Test fun codeCases() {
        assertEquals("helloWorld", expand("{clip:camel}", "Hello world"))
        assertEquals("hello_world", expand("{clip:snake}", "Hello world"))
        assertEquals("hello-world", expand("{clip:slug}", "Hello world"))
    }

    @Test fun unquoteAndNourl() {
        assertEquals("a quote", expand("{clip:unquote}", "“a quote”"))
        assertEquals("see for more", expand("{clip:nourl}", "see https://example.com/x?y=1 for more"))
    }

    @Test fun replaceIsLiteralAndKeepsColonsInItsArguments() {
        assertEquals("a-b-c", expand("{clip:replace(.,-)}", "a.b.c"))
        assertEquals("12h30", expand("{clip:replace(:,h)}", "12:30"))
    }

    @Test fun selectionTakesModifiersToo() {
        assertEquals("SELECTED", expand("{selection:upper}", "clip", selection = "selected"))
    }

    @Test fun unknownModifierLeavesTheTextAsItIs() {
        assertEquals("as is", expand("{clip:nope}", "as is"))
    }

    @Test fun plainTokenStillWorks() {
        assertEquals("[raw]", expand("[{clip}]", "raw"))
    }
}
