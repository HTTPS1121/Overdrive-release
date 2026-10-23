package com.overdrive.app.overlay.glass

import android.graphics.Region
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Lets touches beside the glass (a widget's shadow margin, the gaps in the row) fall through its
 * window to the app beneath. It uses the platform's own touchable-region hook, which the SDK hides;
 * where that's missing the whole window takes touches, as before. [fill] writes the touchable area
 * in the window's coordinates; the platform asks for it on every traversal. Android 12+ still drops a
 * touch that passes through another app's opaque overlay, so there the margins stay as they were.
 */
internal class GlassTouchRegion(private val root: View, private val fill: (Region) -> Unit) {

    private var listener: Any? = null
    // after a failure the window stays wholly touchable rather than half set up
    @Volatile private var broken = false

    /** From the root's onAttachedToWindow, when its tree observer is the window's. */
    fun attach() {
        val a = api ?: return
        if (listener != null) return
        listener = try {
            Proxy.newProxyInstance(a.listener.classLoader, arrayOf(a.listener)) { proxy, m, args ->
                when (m.name) {
                    "onComputeInternalInsets" -> {
                        // this runs inside the window's traversal: a throw here would take the app down
                        val info = args?.getOrNull(0)
                        if (info != null && !broken) {
                            try { a.setInsets.invoke(info, a.regionMode); fill(a.touchable.get(info) as Region) }
                            catch (t: Throwable) { broken = true; Log.w(TAG, "touchable region failed: ${t.message}") }
                        }
                        null
                    }
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "GlassTouchRegion"
                    else -> null
                }
            }.also { a.add.invoke(root.viewTreeObserver, it) }
        } catch (t: Throwable) {
            Log.w(TAG, "touchable region unavailable: ${t.message}")
            null
        }
    }

    fun detach() {
        val a = api ?: return
        val l = listener ?: return
        listener = null
        try { a.remove.invoke(root.viewTreeObserver, l) } catch (_: Throwable) { }
    }

    private class Api {
        val listener: Class<*> = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
        private val info: Class<*> = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
        val setInsets: Method = info.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
        val touchable: Field = info.getField("touchableRegion")
        val regionMode: Int = info.getField("TOUCHABLE_INSETS_REGION").getInt(null)
        val add: Method = ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", listener)
        val remove: Method = ViewTreeObserver::class.java.getMethod("removeOnComputeInternalInsetsListener", listener)
    }

    companion object {
        private const val TAG = "GlassTouchRegion"
        private val api: Api? = try { Api() } catch (t: Throwable) { Log.w(TAG, "no touchable region hook: ${t.message}"); null }
    }
}
