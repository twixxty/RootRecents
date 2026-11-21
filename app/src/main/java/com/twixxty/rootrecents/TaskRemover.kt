package com.twixxty.rootrecents

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
        if (args.isNotEmpty() && args[0] == "daemon") {
            daemon(service)
            return
        }
        if (args.size == 2 && args[0] == "front") {
            println("front:" + moveToFront(service, args[1].toIntOrNull() ?: -1))
            System.exit(0)
        }
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

    /**
     * Stays alive and answers one-line commands on stdin ("front ID", "remove ID ID ..."), so the
     * slow part (starting a Java process) is paid once, in the background, instead of on every tap.
     */
    private fun daemon(service: Any?) {
        val method = service?.let { findRemoveTask(it) }
        println("ready")
        System.out.flush()
        val reader = java.io.BufferedReader(java.io.InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: break
            val parts = line.trim().split(" ")
            when (parts[0]) {
                "front" -> println("front:" + moveToFront(service, parts.getOrNull(1)?.toIntOrNull() ?: -1))
                "remove" -> {
                    for (a in parts.drop(1)) {
                        val id = a.toIntOrNull() ?: continue
                        val ok = try {
                            if (service == null || method == null) false
                            else (method.invoke(service, id) as? Boolean) ?: true
                        } catch (t: Throwable) {
                            false
                        }
                        println("$id:$ok")
                    }
                    println("end")
                }
                "ping" -> println("pong")
            }
            System.out.flush()
        }
        System.exit(0)
    }

    /** Brings an existing task forward exactly as it was (same screen inside the app). */
    private fun moveToFront(service: Any?, id: Int): Boolean {
        if (service == null || id < 0) return false
        val methods = service.javaClass.methods.filter { it.name == "moveTaskToFront" }
        for (pkg in listOf("com.android.shell", "android")) {
            for (m in methods) {
                try {
                    val t = m.parameterTypes
                    val args = arrayOfNulls<Any>(t.size)
                    var ints = 0
                    for (i in t.indices) {
                        args[i] = when {
                            t[i] == String::class.java -> pkg
                            t[i] == Int::class.javaPrimitiveType -> if (ints++ == 0) id else 0
                            else -> null
                        }
                    }
                    m.invoke(service, *args)
                    return true
                } catch (e: Throwable) {
                    // try the next signature / package name
                }
            }
        }
        return false
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
