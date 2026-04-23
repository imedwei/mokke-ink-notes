package com.writer.view.ink

/**
 * Picks the best available [InkController] for this device.
 *
 * The decision is deferred to [InkController.attach]: each candidate's first
 * attach call either succeeds (returns true) or logs the failure and returns
 * false. [com.writer.view.HandwritingCanvasView] holds the controller directly,
 * so a failed attach simply leaves [InkController.isActive] false and the
 * Canvas fallback engages.
 *
 * Selection order at construction time:
 *  1. Bigme — if `Build.MANUFACTURER == "Bigme"`, use the xrz refresh-mode path.
 *  2. Onyx — default for everything else; fails cleanly on non-Onyx devices.
 *
 * [NoopInkController] is not reachable from here — controllers report their
 * own inactivity via `isActive = false` on failed attach.
 */
object InkControllerFactory {
    fun create(): InkController =
        if (BigmeInkController.isBigmeDevice()) BigmeInkController()
        else OnyxInkController()
}
