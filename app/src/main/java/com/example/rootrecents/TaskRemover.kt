package com.example.rootrecents

/**
 * Runs as root through app_process (see [Actions.removeTasks]) and asks the system to remove tasks
 * from recents, exactly like swiping a card away in the stock recents screen: the task is closed,
 * the app's process is not force-stopped.
 *
 * Everything is reflection because the API is hidden. app_process is not subject to the
 * hidden-API restrictions that apply to normal apps.
 */
object TaskRemover {

    @JvmStatic
    fun main(args: Array<String>) {
        val service = taskService()
        val method = service?.let { findRemoveTask(it) }
        for (a in args) {
            val id = a.toIntOrNull() ?: continue
            val ok = try {
                if (service == null || method == null) false
                else (method.invoke(service, id) as? Boolean) ?: true
            } catch (t: Throwable) {
                false
            }
            println("$id:$ok")
        }
        System.exit(0)
    }

    private fun taskService(): Any? {
        // Android 10+
        try {
            val c = Class.forName("android.app.ActivityTaskManager")
            return c.getMethod("getService").invoke(null)
        } catch (t: Throwable) {
            // fall through
        }
        // Android 9 and older
        try {
            val c = Class.forName("android.app.ActivityManager")
            return c.getMethod("getService").invoke(null)
        } catch (t: Throwable) {
            return null
        }
    }

    private fun findRemoveTask(service: Any): java.lang.reflect.Method? {
        val intType = Int::class.javaPrimitiveType
        for (name in listOf("android.app.IActivityTaskManager", "android.app.IActivityManager")) {
            try {
                val iface = Class.forName(name)
                if (iface.isInstance(service)) return iface.getMethod("removeTask", intType)
            } catch (t: Throwable) {
                // try next
            }
        }
        return null
    }
}
