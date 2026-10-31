package com.jepongdevxyz.idefcs.fable

import java.util.Collections

/**
 * Pending "install this SDK platform?" requests from Devxyz Fable, decided
 * by the user with an Allow/Deny card in the Fable panel — the same way
 * Spidey asks before acting. The build watcher creates a request when a
 * build fails because an SDK platform is missing; nothing is installed
 * until the user allows it. A denial only dismisses the card for that
 * failure: the next failed build asks again, just like Spidey would.
 */
object FablePendingInstall {

  private val pending = Collections.synchronizedMap(mutableMapOf<String, Int>())
  private val listeners = Collections.synchronizedList(mutableListOf<() -> Unit>())

  /**
   * Creates a request unless one is already pending for the project.
   * Returns true when a NEW request was created.
   */
  fun request(projectPath: String, api: Int): Boolean {
    val isNew = !pending.containsKey(projectPath)
    pending[projectPath] = api
    if (isNew) notifyChanged()
    return isNew
  }

  fun pendingApi(projectPath: String): Int? = pending[projectPath]

  /** The user allowed: clear the request so the caller can run the install. */
  fun consumeAllowed(projectPath: String): Int? {
    val api = pending.remove(projectPath)
    if (api != null) notifyChanged()
    return api
  }

  /** The user denied: clear the request; the next failed build asks again. */
  fun deny(projectPath: String) {
    if (pending.remove(projectPath) != null) {
      notifyChanged()
    }
  }

  fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  private fun notifyChanged() {
    val copy = synchronized(listeners) { listeners.toList() }
    for (listener in copy) {
      try {
        listener()
      } catch (e: Exception) {
        // A broken listener must not break the others.
      }
    }
  }
}
