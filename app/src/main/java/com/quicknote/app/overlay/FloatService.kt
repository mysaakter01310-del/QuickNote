package com.quicknote.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.quicknote.app.R
import com.quicknote.app.repo.VaultRepository
import com.quicknote.app.ui.ImagePickActivity
import com.quicknote.app.ui.QuickAddActivity
import com.quicknote.app.ui.SimpleWatcher
import com.quicknote.app.util.AppScope
import com.quicknote.app.util.Draft
import com.quicknote.app.util.DraftStore
import com.quicknote.app.util.NoteEvents
import com.quicknote.app.util.QuickSaver
import com.quicknote.app.util.ThumbStrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Floating note: a draggable bubble over any app. Tap it for a small box (title, text, images,
 * Save/Cancel). Drag the bubble onto the X at the bottom to save the note and remove the bubble.
 */
class FloatService : Service() {

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "float_note"
        private const val NID = 41
        private var bubbleX = -1
        private var bubbleY = -1
        private var miniX = -1
        private var miniY = -1

        fun show(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, FloatService::class.java))
        }

        fun hide(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var themed: Context
    private val main = Handler(Looper.getMainLooper())
    private var bubble: View? = null
    private var mini: View? = null
    private var miniLp: WindowManager.LayoutParams? = null
    private var miniEdit: EditText? = null
    private var closeView: View? = null
    private var undoView: View? = null
    private var undoExpire: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        themed = ContextThemeWrapper(this, R.style.Theme_QuickNote)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Floating note", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, QuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("QuickNote floating note")
            .setContentText("Tap to open the full popup")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DraftStore.ensure(this)
        if (!Settings.canDrawOverlays(this) || (intent == null && DraftStore.draft.isBlank())) {
            stopSelf()
            return START_NOT_STICKY
        }
        DraftStore.setListener(this) { refreshMini() }
        if (bubble == null && mini == null) showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        removeBubble()
        removeMini()
        hideClose()
        removeUndo()
        DraftStore.clearListener(this)
        super.onDestroy()
    }

    // ---------------- helpers ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun params(w: Int, h: Int, flags: Int) =
        WindowManager.LayoutParams(w, h, overlayType(), flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    // ---------------- bubble ----------------

    private fun showBubble() {
        removeMini()
        if (bubble != null) return
        val size = dp(52)
        val dm = resources.displayMetrics
        val v = ImageView(themed).apply {
            setImageResource(R.drawable.ic_note)
            setColorFilter(Color.WHITE)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = circle(0xFF1E88E5.toInt())
            elevation = dp(6).toFloat()
        }
        val lp = params(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        lp.x = if (bubbleX >= 0) bubbleX else dm.widthPixels - size - dp(8)
        lp.y = if (bubbleY >= 0) bubbleY else dm.heightPixels / 3
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt()
                        val dy = (e.rawY - sy).toInt()
                        if (!moved && abs(dx) + abs(dy) > dp(8)) { moved = true; showClose() }
                        if (moved) {
                            lp.x = (ox + dx).coerceIn(0, dm.widthPixels - size)
                            lp.y = (oy + dy).coerceIn(0, dm.heightPixels - size)
                            try { wm.updateViewLayout(view, lp) } catch (ex: Exception) { }
                            setCloseHot(overClose(lp, size))
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        bubbleX = lp.x; bubbleY = lp.y
                        if (moved) {
                            val hit = overClose(lp, size)
                            hideClose()
                            if (hit && e.actionMasked == MotionEvent.ACTION_UP) saveAndClose()
                        } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                            showMini()
                        }
                    }
                }
                return true
            }
        })
        wm.addView(v, lp)
        bubble = v
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bubble = null
    }

    private fun closeCenter(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return Pair(dm.widthPixels / 2, dm.heightPixels - dp(110))
    }

    private fun overClose(lp: WindowManager.LayoutParams, size: Int): Boolean {
        val (cx, cy) = closeCenter()
        return hypot((lp.x + size / 2 - cx).toDouble(), (lp.y + size / 2 - cy).toDouble()) < dp(70)
    }

    private fun showClose() {
        if (closeView != null) return
        val s = dp(60)
        val (cx, cy) = closeCenter()
        val v = ImageView(themed).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = circle(0xCC444444.toInt())
        }
        val lp = params(s, s, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        lp.x = cx - s / 2
        lp.y = cy - s / 2
        wm.addView(v, lp)
        closeView = v
    }

    private fun setCloseHot(hot: Boolean) {
        val v = closeView ?: return
        v.scaleX = if (hot) 1.25f else 1f
        v.scaleY = if (hot) 1.25f else 1f
        (v.background as? GradientDrawable)?.setColor(if (hot) 0xFFE53935.toInt() else 0xCC444444.toInt())
    }

    private fun hideClose() {
        closeView?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        closeView = null
    }

    // ---------------- mini box ----------------

    private fun showMini() {
        removeBubble()
        if (mini != null) return
        val v = LayoutInflater.from(themed).inflate(R.layout.float_mini, null)
        val d = DraftStore.draft
        val title = v.findViewById<EditText>(R.id.miniTitle)
        val body = v.findViewById<EditText>(R.id.miniBody)
        title.setText(d.title)
        body.setText(d.body)
        title.addTextChangedListener(SimpleWatcher { DraftStore.draft.title = it; DraftStore.save(this) })
        body.addTextChangedListener(SimpleWatcher { DraftStore.draft.body = it; DraftStore.save(this) })
        v.findViewById<TextView>(R.id.miniFolder).text = d.folderName

        val dm = resources.displayMetrics
        val lp = params(
            dp(290), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )
        lp.x = if (miniX >= 0) miniX else (dm.widthPixels - dp(290)) / 2
        lp.y = if (miniY >= 0) miniY else dm.heightPixels / 6
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        miniLp = lp

        // Tap a text field -> the box takes keyboard focus. Tap outside -> it lets go, so the app behind stays usable.
        val focusTouch = View.OnTouchListener { view, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                miniEdit = view as? EditText
                setMiniFocusable(true)
            }
            false
        }
        title.setOnTouchListener(focusTouch)
        body.setOnTouchListener(focusTouch)
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) setMiniFocusable(false)
            false
        }

        // drag by the header strip
        v.findViewById<View>(R.id.miniHeader).setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = (ox + (e.rawX - sx).toInt()).coerceIn(0, dm.widthPixels - dp(290))
                        lp.y = (oy + (e.rawY - sy).toInt()).coerceIn(0, dm.heightPixels - dp(120))
                        miniX = lp.x; miniY = lp.y
                        try { wm.updateViewLayout(v, lp) } catch (ex: Exception) { }
                    }
                }
                return true
            }
        })

        v.findViewById<View>(R.id.miniMin).setOnClickListener { showBubble() }
        v.findViewById<View>(R.id.miniExpand).setOnClickListener { openFull() }
        v.findViewById<View>(R.id.miniAddImage).setOnClickListener {
            setMiniHidden(true)
            startActivity(Intent(this, ImagePickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        v.findViewById<View>(R.id.miniCancel).setOnClickListener {
            QuickSaver.cleanup(DraftStore.draft.images)
            DraftStore.clear(this)
            stopSelf()
        }
        v.findViewById<View>(R.id.miniSave).setOnClickListener { saveAndClose() }

        wm.addView(v, lp)
        mini = v
        refreshMini()
    }

    private fun setMiniFocusable(on: Boolean) {
        val v = mini ?: return
        val lp = miniLp ?: return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val has = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (on != has) {
            lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            try { wm.updateViewLayout(v, lp) } catch (e: Exception) { }
        }
        if (on) {
            val target = miniEdit ?: v.findViewById<EditText>(R.id.miniBody)
            main.postDelayed({
                target.requestFocus()
                imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        } else {
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            v.findFocus()?.clearFocus()
        }
    }

    /** Hides the box (and lets touches through) while the gallery is open. */
    private fun setMiniHidden(hidden: Boolean) {
        val v = mini ?: return
        val lp = miniLp ?: return
        v.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        lp.flags = if (hidden) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try { wm.updateViewLayout(v, lp) } catch (e: Exception) { }
    }

    private fun refreshMini() {
        val v = mini ?: return
        setMiniHidden(false)
        val images = DraftStore.draft.images
        v.findViewById<HorizontalScrollView>(R.id.miniThumbScroll).visibility = if (images.isEmpty()) View.GONE else View.VISIBLE
        ThumbStrip.render(v.findViewById<LinearLayout>(R.id.miniThumbRow), images, 40) { i ->
            val path = images.removeAt(i)
            QuickSaver.cleanup(listOf(path))
            DraftStore.save(this)
            refreshMini()
        }
    }

    private fun removeMini() {
        mini?.let {
            try {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
                wm.removeView(it)
            } catch (e: Exception) { }
        }
        mini = null
        miniLp = null
    }

    private fun openFull() {
        startActivity(Intent(this, QuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        removeMini()
        stopSelf()
    }

    // ---------------- save + undo ----------------

    private fun saveAndClose() {
        val d = DraftStore.draft.copyDeep()
        removeBubble()
        removeMini()
        hideClose()
        if (d.isBlank()) {
            stopSelf()
            return
        }
        DraftStore.clear(this)
        val app = applicationContext
        AppScope.scope.launch {
            val r = try { QuickSaver.save(app, d) } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                if (r == null) {
                    DraftStore.replace(app, d)
                    Toast.makeText(app, "Couldn't save. Your draft is kept.", Toast.LENGTH_LONG).show()
                    if (running) showBubble()
                    return@withContext
                }
                NoteEvents.notifyChanged()
                if (!running) {
                    QuickSaver.cleanup(d.images)
                } else if (d.editNoteUri != null) {
                    Toast.makeText(app, "Updated", Toast.LENGTH_SHORT).show()
                    QuickSaver.cleanup(d.images)
                    stopSelf()
                } else {
                    showUndo(d, r)
                }
            }
        }
    }

    private fun showUndo(d: Draft, r: QuickSaver.Result) {
        removeUndo()
        val dm = resources.displayMetrics
        val row = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { setColor(0xFF323232.toInt()); cornerRadius = dp(8).toFloat() }
        }
        row.addView(TextView(themed).apply {
            text = "Saved to ${r.folderName}"
            setTextColor(Color.WHITE)
            textSize = 14f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(themed).apply {
            text = "UNDO"
            setTextColor(0xFF8AB4F8.toInt())
            textSize = 14f
            setPadding(dp(12), dp(4), 0, dp(4))
            setOnClickListener { undo(d, r) }
        })
        val lp = params(dm.widthPixels - dp(28), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        lp.x = dp(14)
        lp.y = dm.heightPixels - dp(130)
        wm.addView(row, lp)
        undoView = row
        val expire = Runnable {
            removeUndo()
            QuickSaver.cleanup(d.images)
            stopSelf()
        }
        undoExpire = expire
        main.postDelayed(expire, 4000)
    }

    private fun undo(d: Draft, r: QuickSaver.Result) {
        undoExpire?.let { main.removeCallbacks(it) }
        removeUndo()
        val app = applicationContext
        AppScope.scope.launch {
            val repo = VaultRepository(app)
            repo.deleteUri(r.noteUri)
            r.attachments.forEach { repo.deleteUri(it) }
            withContext(Dispatchers.Main) {
                NoteEvents.notifyChanged()
                DraftStore.replace(app, d)
                if (running) showBubble()
            }
        }
    }

    private fun removeUndo() {
        undoView?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        undoView = null
    }
}
