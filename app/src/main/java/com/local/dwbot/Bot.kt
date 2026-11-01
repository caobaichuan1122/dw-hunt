package com.local.dwbot

import kotlinx.coroutines.delay
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar

/** Outcome of one hunting attempt. */
enum class Res {
    /** The march left: squad 1 is out. */
    OK,

    /** The target could not be used (already being rallied, popup missing): just search again. */
    BUSY,

    /** Something unexpected: the main loop stops instead of guessing. */
    FAIL,

    /** The march button turned into "refill stamina". */
    REFILL,

    /** Free-only mode and no free stamina left: wait and try again later. */
    NOSTAM,
}

/**
 * Hunts giant or wandering zombies with squad 1 only.
 * Rules that must hold: only squad 1 marches, stamina comes only from the free claims or the backpack packs (never
 * the diamond rows), and on anything unexpected the loop stops instead of guessing.
 *
 * @param dev screen, taps and template matching
 * @param log receives one line per notable event (also shown on the floating bar)
 * @param maxPacks cap on backpack stamina packs for one run (each refill uses 20); ignored in free-only mode
 * @param freeOnly when stamina runs out, claim only the free stamina buttons and never touch the packs
 * @param wander hunt wandering zombies (single attack) instead of giant zombies (rally)
 * @param level target level to set on the search panel, 0 to leave the game's own setting alone
 */
class Bot(private val dev: Device, private val log: (String) -> Unit, private val maxPacks: Int, private val freeOnly: Boolean = false, private val wander: Boolean = false, private val level: Int = 0) {
    // the level slider only needs to be set once per run
    private var levelSet = false

    // set when the last stamina dialog had no free claim left, so hunt() can wait instead of stopping
    private var noFree = false
    var packsUsed = 0
        private set
    var refills = 0
        private set

    // vertical centres of the three squad avatars at the right edge of the march popup (reference coordinates)
    private val squadY = intArrayOf(1326, 1470, 1617)

    // HSV ranges (OpenCV scale: H 0..179): the yellow selection ring of the chosen squad, the green progress arc of a busy squad
    private val yellowLo = Scalar(20.0, 140.0, 200.0)
    private val yellowHi = Scalar(35.0, 255.0, 255.0)
    // the selection ring can be paler (saturation ~125) depending on the background, so the ring check uses a looser range
    private val ringLo = Scalar(18.0, 100.0, 215.0)
    private val greenLo = Scalar(40.0, 100.0, 100.0)
    private val greenHi = Scalar(85.0, 255.0, 255.0)

    // ---- screen points (reference tablet coordinates, scaled by Device) ----

    /** Magnifier button on the world map that opens the search panel. */
    private fun magnifier() = dev.pos(70, 2254, 'L', 'B')
    private fun giantTab() = dev.pos(458, 2030, 'L', 'B')
    private fun wanderTab() = dev.pos(168, 2030, 'L', 'B')
    private fun targetTab() = if (wander) wanderTab() else giantTab()
    // the wandering-zombie tab has an extra auto-clear button, which shifts the slider and the search button left
    private fun searchBtn() = if (wander) dev.pos(1340, 2628, 'C', 'B') else dev.pos(1424, 2628, 'C', 'B')
    private fun levelMinus() = if (wander) dev.pos(389, 2632, 'C', 'B') else dev.pos(472, 2632, 'C', 'B')
    private fun levelPlus() = if (wander) dev.pos(1102, 2632, 'C', 'B') else dev.pos(1183, 2632, 'C', 'B')

    /** "Cancel" in the 'target is already being rallied, continue anyway?' dialog. */
    private fun cancelDlg() = dev.pos(739, 1729, 'C', 'C')

    /** The X of the 'get more stamina' dialog. */
    private fun dialogClose() = dev.pos(1537, 519, 'C', 'C')
    private fun emptyGround() = Point(dev.w * 0.03, dev.h * 0.36) // left edge, outside the popup on phone and tablet

    // The popup sits left of centre on phones and centred on the tablet, so search wide bands.
    private fun roiButton() = dev.frac(0.0, 0.55, 0.85, 0.90)
    private fun roiLimit() = dev.frac(0.0, 0.40, 0.85, 0.62)
    private fun roiDialog() = dev.frac(0.0, 0.0, 1.0, 0.6)

    // ---- squad 1 ----

    /** In the march popup the chosen squad's avatar has a yellow ring. It must be squad 1. */
    private fun squadYellow(screen: Mat): Pair<IntArray, IntArray> {
        val whole = IntArray(3)
        val ring = IntArray(3)
        for (i in 0..2) {
            val r = dev.box(1815, squadY[i] - 75, 1965, squadY[i] + 75, 'R', 'C')
            whole[i] = dev.countHsv(screen, r, yellowLo, yellowHi)
            ring[i] = dev.countHsvRing(screen, r, ringLo, yellowHi, 45.0, 70.0)
        }
        return whole to ring
    }

    /** True when the yellow ring sits on squad 1's avatar (by the whole box, or by the ring alone). */
    private fun slot1Selected(screen: Mat): Boolean {
        val (y, a) = squadYellow(screen)
        val byBox = y[0] >= 40 && y[0] > y[1] && y[0] >= y[2]
        val byRing = a[0] >= 20 && a[0] > a[1] + 10 && a[0] > a[2] + 10 // the ring itself, ignoring yellow elsewhere in the box
        return byBox || byRing
    }

    /** Busy = progress arc, or the blue rally / red fight / red return badge on the first avatar. */
    private fun team1Busy(screen: Mat): Boolean {
        val arc = dev.countHsv(screen, dev.box(1945, 1285, 1985, 1390, 'R', 'C'), greenLo, greenHi)
        if (arc > 300 * dev.s * dev.s) return true
        val roi = dev.box(1800, 1260, 1890, 1355, 'R', 'C')
        return listOf("badge_rally", "badge_fight", "badge_return").any { (dev.best(screen, it, roi)?.first ?: 0.0) >= 0.78 }
    }

    /** Waits until squad 1 has been back for two checks in a row; false after [timeoutMs]. */
    private suspend fun waitTeam1Home(timeoutMs: Long = 420_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        var idle = 0
        while (System.currentTimeMillis() < end) {
            val sc = dev.screenshot()
            val busy = team1Busy(sc)
            sc.release()
            if (busy) {
                idle = 0
                delay(1500)
            } else {
                idle++
                if (idle >= 2) return true
                delay(1000)
            }
        }
        return false
    }

    // ---- navigation ----

    /** Close a leftover march popup or 'target already rallied' dialog without sending anything. */
    private suspend fun clearPopups(sc: Mat): Boolean {
        var cur = sc
        var extra: Mat? = null
        try {
            if (dev.findFast(cur, "dlg_target_busy", dlgRoi(), 0.8) != null) {
                dev.tap(cancelDlg())
                delay(1000)
                extra = dev.screenshot()
                cur = extra
            }
            // a leftover search panel (its 搜索 button) also hides the world-map buttons
            val panel = dev.find(cur, "btn_search", dev.frac(0.0, 0.78, 1.0, 1.0), 0.85) != null
            // a leftover target card from an earlier search (its 集结 button) also hides the bottom bar
            val card = dev.findFast(cur, "btn_rally", dev.frac(0.1, 0.45, 0.9, 0.75), 0.85) != null
            val popup = panel || card || dev.find(cur, "btn_march", roiButton()) != null || dev.find(cur, "btn_refill", roiButton()) != null
            if (popup) {
                dev.tap(emptyGround())
                delay(1000)
                return true
            }
            return false
        } finally {
            extra?.release()
        }
    }

    /** Gets to the world map (the "shelter" button is visible there), closing leftover popups on the way. */
    private suspend fun ensureWorld(): Boolean {
        repeat(5) { round ->
            val sc = dev.screenshot()
            try {
                if (dev.find(sc, "btn_shelter") != null) return true
                if (clearPopups(sc)) return@repeat
                val world = dev.find(sc, "btn_world")
                if (world != null) {
                    dev.tap(world)
                    delay(3000)
                } else {
                    val back = dev.find(sc, "btn_back")
                    if (back != null) {
                        dev.tap(back)
                        delay(1500)
                    } else if (round >= 1) {
                        // an unknown card (e.g. a wandering-zombie info card) is in the way: tap empty ground once
                        dev.tap(emptyGround())
                        delay(1000)
                    }
                }
            } finally {
                sc.release()
            }
        }
        val sc = dev.screenshot()
        val ok = dev.find(sc, "btn_shelter") != null
        sc.release()
        return ok
    }

    /** Before each round: be on the world map and have squad 1 at home. */
    private suspend fun prepare(): Boolean {
        if (!ensureWorld()) {
            log("不在世界地图，停止")
            return false
        }
        if (!waitTeam1Home()) {
            log("第一队一直没回来，停止")
            return false
        }
        return true
    }

    // ---- stamina ----

    /** In the 'get more' dialog use the x20 button of the backpack row. The diamond row is never touched. */
    private suspend fun tapX20(dlg: Mat): Boolean {
        if (freeOnly) return claimFreeStamina(dlg)
        if (packsUsed + 20 > maxPacks) {
            log("体力包已达上限 $maxPacks，停止")
            return false
        }
        val x20 = dev.find(dlg, "btn_x20", roiDialog(), 0.9)
        if (x20 == null) {
            log("没找到背包行的 x20，关闭对话框")
            dev.tap(dialogClose())
            delay(800)
            dev.tap(emptyGround())
            return false
        }
        dev.tap(x20)
        packsUsed += 20
        refills++
        log("已用 20 个体力包（共 $packsUsed）")
        delay(1500)
        return true
    }

    /**
     * Free-only mode: press the green 领取 buttons of the 'get more' dialog (the hero's free stamina and the 125 supply
     * drop). Packs and the diamond rows are never touched. False when neither is ready.
     */
    private suspend fun claimFreeStamina(dlg: Mat): Boolean {
        val claims = dev.findAll(dlg, "btn_claim_free", roiDialog(), 0.8)
        if (claims.isEmpty()) {
            noFree = true
            log("免费体力都领过了，先不打")
            dev.tap(dialogClose())
            delay(800)
            dev.tap(emptyGround())
            return false
        }
        for (c in claims.sortedBy { it.y }) {
            dev.tap(c)
            delay(1500)
            dev.tap(emptyGround()) // a reward popup may have opened
            delay(600)
        }
        log("领了 ${claims.size} 个免费体力")
        val after = dev.screenshot()
        val stillOpen = dev.findFast(after, "btn_x20", roiDialog(), 0.9) != null
        after.release()
        if (stillOpen) {
            dev.tap(dialogClose())
            delay(1000)
        }
        refills++
        return true
    }

    // ---- rally ----

    /** Screen band where the centred confirmation dialogs appear. */
    private fun dlgRoi() = dev.frac(0.1, 0.3, 0.9, 0.7)

    /**
     * Watch the screen after the march tap until the outcome is clear: the 'target already rallied' dialog (its zoom-in
     * animation can take a moment), the 'refill stamina' dialog, or squad 1 leaving. Gives up after 3 seconds.
     */
    private suspend fun classifyAfterMarch(first: Mat): Res {
        var cur = first
        var own = false
        val end = System.currentTimeMillis() + 3000
        try {
            while (true) {
                if (dev.findFast(cur, "dlg_target_busy", dlgRoi(), 0.8) != null) {
                    dev.tap(cancelDlg()) // target already under rally, don't waste stamina
                    delay(800)
                    dev.tap(emptyGround())
                    log("目标已被别人集结，取消，重新搜索")
                    return Res.BUSY
                }
                if (dev.findFast(cur, "btn_x20", roiDialog(), 0.9) != null) return Res.REFILL // march button was 'refill stamina'
                if (team1Busy(cur)) return Res.OK
                if (System.currentTimeMillis() > end) break
                if (own) cur.release()
                delay(150)
                cur = dev.screenshot()
                own = true
            }
            log("第一队没有出发，停止")
            return Res.FAIL
        } finally {
            if (own) cur.release()
        }
    }

    /** Slow path: read the popup, check everything, then tap march. Also learns where the button is. */
    private suspend fun verifiedMarch(hit: PopupHit, t0: Long): Res {
        var popup = hit.frame
        var own: Mat? = null
        try {
            var march = hit.march
            if (hit.refill != null) {
                val refill = hit.refill
                dev.tap(refill)
                delay(1200)
                val dlg = dev.screenshot()
                val ok = tapX20(dlg)
                dlg.release()
                if (!ok) return if (noFree) Res.NOSTAM else Res.FAIL
                if (freeOnly) {
                    // closing the dialog also closed the march popup: search again with the refilled stamina
                    dev.tap(emptyGround())
                    delay(600)
                    return Res.BUSY
                }
                own = dev.screenshot()
                popup = own
                march = dev.findFast(popup, "btn_march", roiButton())
            }
            if (march == null || (!wander && dev.findFast(popup, "lbl_rally_limit", roiLimit(), 0.9) == null)) {
                log(if (wander) "没出现行军按钮，停止" else "不是巨型丧尸集结框，停止")
                dev.tap(emptyGround())
                return Res.FAIL
            }
            // the yellow ring may not be fully drawn right after the popup opens, look again before giving up
            var recheck = 0
            while (!slot1Selected(popup) && recheck < 3) {
                recheck++
                delay(500)
                if (popup !== hit.frame) popup.release()
                popup = dev.screenshot()
                own = popup
            }
            if (!slot1Selected(popup)) {
                val (y, a) = squadYellow(popup)
                log("选中的不是第一队，不出兵（整框 ${y.joinToString("/")}，环 ${a.joinToString("/")}）")
                org.opencv.imgcodecs.Imgcodecs.imwrite(java.io.File(dev.filesDir(), "dbg_popup.png").path, popup)
                dev.tap(emptyGround())
                return Res.FAIL
            }
            dev.tap(march)
            val tTap = (System.currentTimeMillis() - t0) / 1000.0
            delay(400)
            val after = dev.screenshot()
            val res = classifyAfterMarch(after)
            after.release()
            if (res == Res.OK) log((if (wander) "已出兵攻击，点行军用了 %.1f 秒" else "集结已发出，点行军用了 %.1f 秒").format(tTap))
            return if (res == Res.REFILL) Res.FAIL else res
        } finally {
            own?.release()
        }
    }

    /** Poll the screen until [name] shows up inside [roi]; returns that frame (caller releases it) and the match centre. */
    private suspend fun pollFor(name: String, roi: Rect, timeoutMs: Long, thr: Double = 0.85): Pair<Mat, Point>? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val sc = dev.screenshot()
            val p = dev.findFast(sc, name, roi, thr)
            if (p != null) return sc to p
            sc.release()
            delay(60)
        }
        return null
    }

    /** The frame that showed the popup (the caller releases it) and where its march / refill button is. */
    private class PopupHit(val frame: Mat, val march: Point?, val refill: Point?)

    /** Wait for the march popup: either the march button, or the 'refill stamina' button that replaces it. */
    private suspend fun waitPopup(timeoutMs: Long): PopupHit? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val sc = dev.screenshot()
            val march = dev.findFast(sc, "btn_march", roiButton())
            val refill = if (march == null) dev.findFast(sc, "btn_refill", roiButton()) else null
            if (march != null || refill != null) return PopupHit(sc, march, refill)
            sc.release()
            delay(40)
        }
        return null
    }

    /**
     * Opens the search panel, selects the target tab and level, searches, and returns the button of the result card
     * (集结 for giants, 攻击 for wanderers). The game sometimes closes the panel by itself after the magnifier tap,
     * so every step is confirmed on screen.
     */
    private suspend fun findTarget(): Point? {
        val panelRoi = dev.frac(0.0, 0.78, 1.0, 1.0)
        val tabRoi = dev.frac(0.0, 0.62, 0.7, 0.88)
        for (attempt in 1..3) {
            dev.tap(magnifier())
            // one frame tells both: is the panel open (the highlighted giant tab or the search button is visible)
            var panel = false
            var tabOn = false
            val end = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < end) {
                val sc = dev.screenshot()
                tabOn = dev.findFast(sc, "tab_giant_on", tabRoi, 0.8) != null
                panel = tabOn || dev.findFast(sc, "btn_search", panelRoi, 0.85) != null
                sc.release()
                if (panel) break
                delay(40)
            }
            if (!panel) {
                log("搜索面板没打开（第 $attempt 次），重试")
                continue
            }
            // The highlight can be stale: the game may still search the previous tab until the tab is actually tapped,
            // so always tap it. Whether it took is checked later by the result card itself (集结 vs 攻击).
            dev.tap(targetTab())
            delay(150)
            if (level > 0 && !levelSet) {
                // Lv.1 is the floor: run the slider down to it, then up to the wanted level (stops at the account's maximum)
                delay(400)
                repeat(45) { dev.tap(levelMinus(), 30) }
                repeat(level - 1) { dev.tap(levelPlus(), 30) }
                levelSet = true
                log("目标等级已设为 Lv.$level")
                delay(300)
            }
            dev.tap(searchBtn())
            val hit = pollFor(if (wander) "btn_attack" else "btn_rally", dev.frac(0.1, 0.45, 0.9, 0.75), 3500)
            if (hit != null) {
                hit.first.release()
                return hit.second
            }
            log("搜索后没出现集结按钮（第 $attempt 次），重试")
        }
        return null
    }

    /** One full attempt: search a target, open its march popup, check everything, march. Covers both target types. */
    private suspend fun rallyGiant(): Res {
        val t0 = System.currentTimeMillis()
        fun at() = "%.1f".format((System.currentTimeMillis() - t0) / 1000.0)
        val rally = findTarget()
        if (rally == null) {
            val dbg = dev.screenshot()
            log(if (wander) "重试 3 次仍没搜到游荡丧尸，停止" else "重试 3 次仍没搜到巨型丧尸，停止")
            org.opencv.imgcodecs.Imgcodecs.imwrite(java.io.File(dev.filesDir(), "dbg_search.png").path, dbg)
            dbg.release()
            return Res.FAIL
        }
        val tSearch = at()
        dev.tap(rally)
        val popup = waitPopup(4000)
        if (popup == null) {
            log("集结后没出现出兵框，清理后重新搜索")
            dev.tap(emptyGround())
            delay(500)
            return Res.BUSY
        }
        val tPopup = at()
        try {
            log("搜到目标 ${tSearch}s，出兵框 ${tPopup}s")
            return verifiedMarch(popup, t0)
        } finally {
            popup.frame.release()
        }
    }

    /** Main loop: [count] successful marches, stopping early on anything unexpected. */
    suspend fun hunt(count: Int) {
        dev.screenshot().release() // learn the screen size before any coordinate is used
        var done = 0
        var busy = 0
        log("开始，目标 $count 轮，屏幕 ${dev.w}x${dev.h}")
        while (done < count) {
            log("第 ${done + 1}/$count 轮：等第一队回家")
            if (!prepare()) return
            when (rallyGiant()) {
                Res.OK -> {
                    done++
                    busy = 0
                }
                Res.BUSY -> {
                    busy++
                    if (busy > 8) {
                        log("目标总是被抢，停止")
                        return
                    }
                    delay(1000)
                }
                Res.NOSTAM -> {
                    noFree = false
                    log("没有免费体力，10 分钟后再看")
                    delay(10 * 60_000L)
                }
                else -> return
            }
        }
        log("完成 $done 轮，用体力包 $packsUsed 个")
    }
}
