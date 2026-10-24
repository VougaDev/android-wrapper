package com.vougadev.shell.web

import java.util.Locale

/**
 * Pull-to-refresh only when the PAGE is at its top. The WebView's own scroll is zero inside a scrolled modal body or
 * an inner list, so a pull there would reload the page under the person's form. On every touch down the shell asks
 * the page [script] for the point touched; it answers `"block"` when a modal or off-canvas panel is open, or when any
 * element under the finger is itself scrolled down - then the pull is the element's scroll, not a refresh.
 */
object PullGuard {

    /** [x], [y] in CSS pixels (the touch point divided by the screen density). */
    fun script(x: Float, y: Float): String {
        val px = String.format(Locale.ROOT, "%.1f", x)
        val py = String.format(Locale.ROOT, "%.1f", y)
        return "(function(){try{" +
            "if(document.querySelector('.modal.show,.offcanvas.show')){return 'block';}" +
            "var e=document.elementFromPoint($px,$py);" +
            "while(e&&e!==document.body&&e!==document.documentElement){if(e.scrollTop>0){return 'block';}e=e.parentElement;}" +
            "return 'pull';}catch(err){return 'pull';}})()"
    }

    fun blocks(result: String?): Boolean = result == "\"block\""
}
