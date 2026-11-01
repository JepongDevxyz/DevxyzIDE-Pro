/*
 *  This file is part of DevxyzIDE.
 *
 *  DevxyzIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DevxyzIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with DevxyzIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.jepongdevxyz.idefcs.fable

import java.util.Collections

/** One file change inside a patch proposal. Paths are project-relative. */
data class FableFileEdit(
  val relativePath: String,
  val isCreate: Boolean,
  /** Exact existing text to replace (edits only; null for creates). */
  val search: String?,
  /** Replacement text, or the full content of a created file. */
  val replace: String
)

/**
 * A patch proposed by the AI: a human-readable summary plus the exact file
 * edits. It is only ever applied after the user taps Apply on the card —
 * never automatically (plan §§8–9).
 */
data class FablePatchProposal(
  val id: Long,
  val projectPath: String,
  val kind: FableAiCallKind,
  val summary: String,
  val edits: List<FableFileEdit>,
  /** Set for auto-fix proposals: identifies the incident being fixed. */
  val incidentKey: String?
)

/**
 * A pending "may I send these files to Gemini?" request, raised by the
 * build watcher when a build fails with a real code error. Nothing leaves
 * the phone until the user allows it on the card (privacy rail, plan §5).
 */
data class FableAiFixRequest(
  val projectPath: String,
  val incidentKey: String,
  val errors: List<FableClassifier.ErrorLocation>,
  val buildTail: String,
  /** Which AI patch attempt this would be (1 or 2). */
  val attempt: Int,
  /**
   * Extra project files sent with the request because the failure class
   * points at them (build scripts, settings, properties, version
   * catalog). For non-compile failures these ARE the evidence.
   */
  val contextFiles: List<String> = emptyList()
)

/**
 * The two pending AI decisions, one of each per project, rendered as cards
 * in the Fable panel (and the Fable home screen). Listener pattern mirrors
 * [FablePendingInstall].
 */
object FablePendingAi {

  private val fixRequests = Collections.synchronizedMap(mutableMapOf<String, FableAiFixRequest>())
  private val proposals = Collections.synchronizedMap(mutableMapOf<String, FablePatchProposal>())
  private val listeners = Collections.synchronizedList(mutableListOf<() -> Unit>())

  /** Creates an ask-to-send request unless one is already pending. */
  fun requestFix(request: FableAiFixRequest): Boolean {
    val isNew = !fixRequests.containsKey(request.projectPath)
    fixRequests[request.projectPath] = request
    if (isNew) notifyChanged()
    return isNew
  }

  fun pendingFix(projectPath: String): FableAiFixRequest? = fixRequests[projectPath]

  /** The user allowed sending: hand the request to the caller to run. */
  fun consumeFix(projectPath: String): FableAiFixRequest? {
    val request = fixRequests.remove(projectPath)
    if (request != null) notifyChanged()
    return request
  }

  fun denyFix(projectPath: String) {
    if (fixRequests.remove(projectPath) != null) notifyChanged()
  }

  /** Publishes a patch proposal card for the project. */
  fun propose(proposal: FablePatchProposal) {
    proposals[proposal.projectPath] = proposal
    notifyChanged()
  }

  fun pendingProposal(projectPath: String): FablePatchProposal? = proposals[projectPath]

  /** The user tapped Apply: hand the proposal to the caller to apply. */
  fun consumeProposal(projectPath: String): FablePatchProposal? {
    val proposal = proposals.remove(projectPath)
    if (proposal != null) notifyChanged()
    return proposal
  }

  fun denyProposal(projectPath: String) {
    if (proposals.remove(projectPath) != null) notifyChanged()
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
