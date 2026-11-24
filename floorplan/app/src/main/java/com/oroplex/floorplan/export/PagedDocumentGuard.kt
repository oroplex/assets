package com.oroplex.floorplan.export

/**
 * Lifecycle bookkeeping for a paged document whose current page must be finished before the document
 * may be closed, such as android.graphics.pdf.PdfDocument (its `close()` throws
 * "Current page not finished!" otherwise and then never releases its native memory).
 *
 * [run] guarantees that, on every path, an open page is finished and the document is closed, and that
 * cleanup never replaces the error that made the block fail: problems during cleanup are attached to
 * the original throwable as suppressed exceptions, so an OutOfMemoryError (or a drawing bug) reaches
 * the caller unchanged.
 *
 * Not thread-safe; one guard per document.
 */
internal class PagedDocumentGuard<P : Any>(
    private val finishPage: (P) -> Unit,
    private val closeDocument: () -> Unit,
) {
    private var openPage: P? = null

    /** Records [page] as the page being drawn; returns it for chaining. */
    fun started(page: P): P {
        check(openPage == null) { "Finish the current page before starting another" }
        openPage = page
        return page
    }

    /** Finishes [page] (normally the one passed to [started]). */
    fun finish(page: P) {
        if (openPage === page) openPage = null
        finishPage(page)
    }

    fun <T> run(block: PagedDocumentGuard<P>.() -> T): T {
        val result = try {
            block()
        } catch (primary: Throwable) {
            cleanUp(primary)
            throw primary
        }
        // Success. A page left open by mistake is finished first so close() can succeed; an error
        // here is the only error, so it propagates.
        try {
            openPage?.let { finish(it) }
        } catch (t: Throwable) {
            cleanUp(t)
            throw t
        }
        closeDocument()
        return result
    }

    private fun cleanUp(primary: Throwable) {
        openPage?.let { page ->
            openPage = null
            try {
                finishPage(page)
            } catch (t: Throwable) {
                primary.addSuppressedSafely(t)
            }
        }
        try {
            closeDocument()
        } catch (t: Throwable) {
            primary.addSuppressedSafely(t)
        }
    }

    private fun Throwable.addSuppressedSafely(other: Throwable) {
        if (other !== this) addSuppressed(other)
    }
}
