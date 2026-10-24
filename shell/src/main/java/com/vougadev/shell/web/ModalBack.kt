package com.vougadev.shell.web

/**
 * The back press over an open modal or off-canvas panel (the sidebar on a phone).
 *
 * The site's pages keep no history sentinel for a modal, so back asks the page FIRST: [SCRIPT] hides the topmost
 * `.modal.show` (else an open `.offcanvas.show`) with the page's own `hide()` and answers `"closed"`; with nothing
 * open it answers `"none"`, and only then does back go back in history, or leave.
 */
object ModalBack {

    const val SCRIPT = """(function () {
  var b = window.bootstrap;
  var modals = document.querySelectorAll(".modal.show");
  if (modals.length && b && b.Modal) { b.Modal.getOrCreateInstance(modals[modals.length - 1]).hide(); return "closed"; }
  var panel = document.querySelector(".offcanvas.show");
  if (panel && b && b.Offcanvas) { b.Offcanvas.getOrCreateInstance(panel).hide(); return "closed"; }
  return "none";
})();"""

    /** `evaluateJavascript` hands back the result JSON-encoded: `"closed"` with its quotes. */
    fun closedAModal(result: String?): Boolean = result == "\"closed\""
}
