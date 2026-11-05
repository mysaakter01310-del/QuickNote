package com.quicknote.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.quicknote.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.Executors

/** Background scope that outlives screens, so saves finish even if the screen closes. */
object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** Lets screens know the vault changed so they can refresh in the background. */
object NoteEvents {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var listener: (() -> Unit)? = null
    fun notifyChanged() { main.post { listener?.invoke() } }
}

/** Small downsampled thumbnails, decoded off the main thread and cached. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, uri: Uri?, maxPx: Int) {
        if (uri == null) {
            view.tag = null
            view.setImageDrawable(null)
            return
        }
        val key = "$uri@$maxPx"
        view.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        view.setImageDrawable(null)
        val app = view.context.applicationContext
        pool.execute {
            val bmp = decode(app, uri, maxPx)
            if (bmp != null) cache.put(key, bmp)
            main.post { if (view.tag == key && bmp != null) view.setImageBitmap(bmp) }
        }
    }

    private fun decode(ctx: Context, uri: Uri, maxPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Throwable) {
        null
    }
}

/** Draws a row of small image thumbnails with a remove (x) badge on each. */
object ThumbStrip {
    fun render(row: LinearLayout, images: List<String>, sizeDp: Int, onRemove: (Int) -> Unit) {
        row.removeAllViews()
        val d = row.resources.displayMetrics.density
        val s = (sizeDp * d).toInt()
        images.forEachIndexed { i, path ->
            val cell = FrameLayout(row.context)
            val lp = LinearLayout.LayoutParams(s, s)
            lp.marginEnd = (8 * d).toInt()
            cell.layoutParams = lp
            val iv = ImageView(row.context)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setBackgroundColor(0xFFE0E0E0.toInt())
            cell.addView(iv, FrameLayout.LayoutParams(-1, -1))
            Thumbs.load(iv, Uri.fromFile(File(path)), s * 2)
            val x = ImageView(row.context)
            x.setImageResource(R.drawable.ic_close)
            x.setColorFilter(Color.WHITE)
            x.setBackgroundColor(0xAA000000.toInt())
            val p = (3 * d).toInt()
            x.setPadding(p, p, p, p)
            x.setOnClickListener { onRemove(i) }
            val xs = (20 * d).toInt()
            cell.addView(x, FrameLayout.LayoutParams(xs, xs, Gravity.TOP or Gravity.END))
            row.addView(cell)
        }
    }
}
