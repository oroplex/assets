package com.oroplex.floorplan.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * App AR-3: a failure while drawing a page must reach the caller unchanged, the page must be finished
 * and the document closed (PdfDocument.close() throws over an unfinished page).
 */
class PagedDocumentGuardTest {

    /** Mimics PdfDocument: close() refuses while a page is open. */
    private class FakeDocument(val failFinish: Boolean = false, val failClose: Boolean = false) {
        val log = mutableListOf<String>()
        private var current: String? = null

        fun start(name: String): String {
            check(current == null)
            current = name
            log += "start $name"
            return name
        }

        fun finish(page: String) {
            log += "finish $page"
            if (failFinish) throw IllegalStateException("finish failed")
            current = null
        }

        fun close() {
            log += "close"
            if (current != null) throw IllegalStateException("Current page not finished!")
            if (failClose) throw IllegalStateException("close failed")
        }

        fun guard() = PagedDocumentGuard<String>(::finish, ::close)
    }

    @Test
    fun `success finishes pages in order and closes once`() {
        val doc = FakeDocument()
        val result = doc.guard().run {
            val p1 = started(doc.start("1"))
            finish(p1)
            val p2 = started(doc.start("2"))
            finish(p2)
            "written"
        }
        assertEquals("written", result)
        assertEquals(listOf("start 1", "finish 1", "start 2", "finish 2", "close"), doc.log)
    }

    @Test
    fun `out of memory mid page is rethrown unchanged after finishing the page and closing`() {
        val doc = FakeDocument()
        val oom = OutOfMemoryError("boom")
        try {
            doc.guard().run {
                started(doc.start("1"))
                throw oom
            }
            fail("expected the error")
        } catch (e: OutOfMemoryError) {
            assertSame(oom, e)
            assertEquals(0, e.suppressed.size)
        }
        assertEquals(listOf("start 1", "finish 1", "close"), doc.log)
    }

    @Test
    fun `cleanup failures are attached as suppressed and never mask the primary error`() {
        val doc = FakeDocument(failFinish = true)
        val primary = IllegalArgumentException("drawing failed")
        try {
            doc.guard().run {
                started(doc.start("1"))
                throw primary
            }
            fail("expected the error")
        } catch (e: IllegalArgumentException) {
            assertSame(primary, e)
            val messages = e.suppressed.map { it.message }
            assertTrue(messages.contains("finish failed"))
            assertTrue(messages.contains("Current page not finished!"))
        }
        assertEquals(listOf("start 1", "finish 1", "close"), doc.log)
    }

    @Test
    fun `a failure between pages closes without finishing anything`() {
        val doc = FakeDocument()
        val primary = java.io.IOException("disk full")
        try {
            doc.guard().run {
                finish(started(doc.start("1")))
                throw primary
            }
            fail("expected the error")
        } catch (e: java.io.IOException) {
            assertSame(primary, e)
        }
        assertEquals(listOf("start 1", "finish 1", "close"), doc.log)
    }

    @Test
    fun `a page left open on success is finished before closing`() {
        val doc = FakeDocument()
        doc.guard().run { started(doc.start("1")) }
        assertEquals(listOf("start 1", "finish 1", "close"), doc.log)
    }

    @Test
    fun `a close failure on success propagates`() {
        val doc = FakeDocument(failClose = true)
        try {
            doc.guard().run { finish(started(doc.start("1"))) }
            fail("expected the close failure")
        } catch (e: IllegalStateException) {
            assertEquals("close failed", e.message)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `starting a second page before finishing the first is a bug`() {
        val doc = FakeDocument()
        val guard = doc.guard()
        guard.started("a")
        guard.started("b")
    }
}
