package com.jepongdevxyz.idefcs.fable

import java.util.Collections

/**
 * Live progress of an approved SDK platform install, so the Fable panel
 * can show a real progress bar while sdkmanager works (the install can
 * take minutes on a slow connection). State lives here — not in any view —
 * so it survives the panel being closed and reopened mid-install.
 */
object FableInstallProgress {

  data class State(
    val running: Boolean,
    val api: Int,
    /** 0..100 when sdkmanager reports a percentage, -1 when unknown. */
    val percent: Int,
    val status: String
  )

  @Volatile
  var state: State = State(false, 0, -1, "")
    private set

  private val listeners = Collections.synchronizedList(mutableListOf<() -> Unit>())

  fun begin(api: Int) {
    state = State(true, api, -1, "Sinisimulan ang installer…")
    notifyChanged()
  }

  fun update(percent: Int, status: String) {
    val current = state
    if (!current.running) return
    state = current.copy(percent = percent, status = status)
    notifyChanged()
  }

  fun end() {
    state = State(false, 0, -1, "")
    notifyChanged()
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
