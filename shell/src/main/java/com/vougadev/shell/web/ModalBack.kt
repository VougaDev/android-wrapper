package com.vougadev.shell.web

/**
 * The back press over an open modal, off-canvas panel (the sidebar on a phone), menu or popover.
 *
 * The site's pages keep no history sentinel for them, so back asks the page FIRST: [SCRIPT] closes the topmost one
 * (an open popover, a menu, a modal, an off-canvas panel) with the page's own `hide()` and answers `"closed"`; with
 * nothing open it answers `"none"`, and only then does back go back in history, or leave. A Back that leaves the
 * screen while a menu is open throws away where the person was.
 */
object ModalBack {

    const val SCRIPT = """(function () {
  var b = window.bootstrap;
  var pop = document.querySelector(".popover.show");
  var tip = pop && pop.id && document.querySelector('[aria-describedby="' + pop.id + '"]');
  if (tip && b && b.Popover && b.Popover.getInstance(tip)) { b.Popover.getInstance(tip).hide(); return "closed"; }
  var menu = document.querySelector(".dropdown-menu.show");
  var toggle = menu && menu.parentElement && menu.parentElement.querySelector("[data-bs-toggle=dropdown]");
  if (toggle && b && b.Dropdown) { b.Dropdown.getOrCreateInstance(toggle).hide(); return "closed"; }
  var modals = document.querySelectorAll(".modal.show");
  if (modals.length && b && b.Modal) { b.Modal.getOrCreateInstance(modals[modals.length - 1]).hide(); return "closed"; }
  var panel = document.querySelector(".offcanvas.show");
  if (panel && b && b.Offcanvas) { b.Offcanvas.getOrCreateInstance(panel).hide(); return "closed"; }
  return "none";
})();"""

    /** `evaluateJavascript` hands back the result JSON-encoded: `"closed"` with its quotes. */
    fun closedAModal(result: String?): Boolean = result == "\"closed\""
}
