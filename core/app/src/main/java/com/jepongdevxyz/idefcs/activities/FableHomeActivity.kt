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

package com.jepongdevxyz.idefcs.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.jepongdevxyz.idefcs.activities.editor.EditorActivityKt
import com.jepongdevxyz.idefcs.app.EdgeToEdgeIDEActivity
import com.jepongdevxyz.idefcs.databinding.ActivityFableHomeBinding
import com.jepongdevxyz.idefcs.fable.FableAi
import com.jepongdevxyz.idefcs.fable.FableAiCallKind
import com.jepongdevxyz.idefcs.fable.FableArchiveReader
import com.jepongdevxyz.idefcs.fable.FableBuildWatcher
import com.jepongdevxyz.idefcs.fable.FableChatMessage
import com.jepongdevxyz.idefcs.fable.FableChatStore
import com.jepongdevxyz.idefcs.fable.FableChatUi
import com.jepongdevxyz.idefcs.fable.FableIndexStore
import com.jepongdevxyz.idefcs.fable.FableIntake
import com.jepongdevxyz.idefcs.fable.FableLocalBrain
import com.jepongdevxyz.idefcs.fable.FablePatcher
import com.jepongdevxyz.idefcs.fable.FablePendingAi
import com.jepongdevxyz.idefcs.fable.FableRole
import com.jepongdevxyz.idefcs.preferences.internal.GeneralPreferences
import com.jepongdevxyz.idefcs.projects.IProjectManager
import com.jepongdevxyz.idefcs.resources.R
import com.jepongdevxyz.idefcs.tasks.TaskExecutor
import java.io.File
import java.util.concurrent.Callable

/**
 * Devxyz Fable on the home screen. The user can chat with Fable before any
 * project is open, read attachments, and send a project ZIP — Fable then
 * imports it, builds its map, opens it and starts its first build.
 */
class FableHomeActivity : EdgeToEdgeIDEActivity() {

  private var _binding: ActivityFableHomeBinding? = null
  private val binding: ActivityFableHomeBinding
    get() = checkNotNull(_binding) { "Activity has been destroyed" }

  private val chatListener: () -> Unit = {
    _binding?.root?.post { renderChat() }
  }

  private val aiListener: () -> Unit = {
    _binding?.root?.post { renderPatchCard() }
  }

  private val attachmentLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        onAttachmentPicked(uri)
      }
    }

  override fun bindLayout(): View {
    _binding = ActivityFableHomeBinding.inflate(layoutInflater)
    return binding.root
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setSupportActionBar(binding.fableHomeToolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    binding.fableHomeToolbar.setNavigationOnClickListener {
      onBackPressedDispatcher.onBackPressed()
    }

    binding.fableHomeSwitch.isChecked = GeneralPreferences.fableEnabled
    binding.fableHomeSwitch.setOnCheckedChangeListener { _, checked ->
      GeneralPreferences.fableEnabled = checked
      renderChat()
    }
    binding.fableHomeSend.setOnClickListener { sendChatMessage() }
    binding.fableHomeAttach.setOnClickListener {
      attachmentLauncher.launch(arrayOf("*/*"))
    }
    binding.fableHomePatchApply.setOnClickListener { onPatchApply() }
    binding.fableHomePatchDeny.setOnClickListener { onPatchDeny() }

    FableChatStore.addListener(chatListener)
    FablePendingAi.addListener(aiListener)
    setupKeyboardLift(binding.root)
    registerImeFirstBack()
    renderChat()
  }

  /** Navigation Back closes the keyboard first while it is open. */
  private fun registerImeFirstBack() {
    onBackPressedDispatcher.addCallback(
      this,
      object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          val insets = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)
          val imeVisible = insets?.isVisible(
            androidx.core.view.WindowInsetsCompat.Type.ime()
          ) == true
          if (imeVisible) {
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
              .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
          } else {
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
          }
        }
      }
    )
  }

  /** Lifts the chat content above the keyboard while it is open. */
  private fun setupKeyboardLift(root: android.view.View) {
    var lift = 0
    root.viewTreeObserver.addOnGlobalLayoutListener {
      val rect = android.graphics.Rect()
      root.getWindowVisibleDisplayFrame(rect)
      val diff = root.rootView.height - rect.bottom
      val threshold = (100 * root.resources.displayMetrics.density).toInt()
      val newLift = if (diff > threshold) diff else 0
      if (newLift != lift) {
        lift = newLift
        root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, newLift)
      }
    }
  }

  override fun onDestroy() {
    FableChatStore.removeListener(chatListener)
    FablePendingAi.removeListener(aiListener)
    super.onDestroy()
    _binding = null
  }

  /** The chat scope: the open project when there is one, otherwise home. */
  private fun scopePath(): String {
    val path = try {
      IProjectManager.getInstance().projectDirPath
    } catch (e: Exception) {
      null
    }
    return if (path.isNullOrBlank()) HOME_SCOPE else path
  }

  private fun renderChat() {
    val binding = _binding ?: return
    binding.fableHomeSwitch.isChecked = GeneralPreferences.fableEnabled

    if (!GeneralPreferences.fableEnabled) {
      binding.fableHomeList.removeAllViews()
      binding.fableHomeStatus.text = ""
      binding.fableHomeEmpty.visibility = View.VISIBLE
      binding.fableHomeEmpty.text = getString(R.string.msg_fable_chat_off)
      return
    }

    val scopePath = scopePath()
    val log = FableChatStore.load(filesDir, scopePath)
    FableChatUi.renderMessages(binding.fableHomeList, log)
    binding.fableHomeEmpty.visibility = if (log.messages.isEmpty()) View.VISIBLE else View.GONE
    binding.fableHomeEmpty.text = getString(R.string.msg_fable_home_empty)
    binding.fableHomeStatus.text =
      if (scopePath == HOME_SCOPE) getString(R.string.msg_fable_scope)
      else scopePath

    FableChatStore.markAllRead(filesDir, scopePath)
    renderPatchCard()
    binding.fableHomeScroll.post { binding.fableHomeScroll.fullScroll(View.FOCUS_DOWN) }
  }

  /** The patch proposal card for the open project, when one is pending. */
  private fun renderPatchCard() {
    val binding = _binding ?: return
    val scopePath = scopePath()
    val proposal = if (
      GeneralPreferences.fableEnabled && scopePath != HOME_SCOPE
    ) {
      FablePendingAi.pendingProposal(scopePath)
    } else {
      null
    }
    if (proposal == null) {
      binding.fableHomePatchCard.visibility = View.GONE
      return
    }
    binding.fableHomePatchTitle.text = getString(
      if (proposal.kind == FableAiCallKind.AUTO_FIX) {
        R.string.msg_fable_patch_card_title_fix
      } else {
        R.string.msg_fable_patch_card_title_change
      }
    )
    binding.fableHomePatchBody.text =
      proposal.summary + "\n\n" + FablePatcher.previewText(proposal)
    binding.fableHomePatchCard.visibility = View.VISIBLE
  }

  private fun onPatchApply() {
    val scopePath = scopePath()
    if (scopePath == HOME_SCOPE) return
    val projectDir = File(scopePath)
    if (!projectDir.isDirectory) return
    val proposal = FablePendingAi.consumeProposal(scopePath) ?: run {
      renderPatchCard()
      return
    }
    renderPatchCard()
    val appFilesDir = filesDir
    TaskExecutor.executeAsync(
      Callable {
        FablePatcher.apply(appFilesDir, projectDir, proposal)
      }
    ) { result ->
      when (result) {
        is FablePatcher.ApplyResult.Applied -> {
          FableChatStore.append(
            appFilesDir,
            scopePath,
            FableChatMessage(
              FableRole.FABLE,
              "Applied — I saved a backup snapshot of your files first. " +
                "Rebuilding now: if the build passes, the fix stands; " +
                "if it fails, I'll roll everything back automatically.",
              System.currentTimeMillis()
            ),
            countUnread = false
          )
          FableBuildWatcher.rebuildProject(appFilesDir, scopePath)
        }
        is FablePatcher.ApplyResult.Rejected -> {
          FableChatStore.append(
            appFilesDir,
            scopePath,
            FableChatMessage(
              FableRole.NOTICE,
              "I didn't apply that change: ${result.detail}. Your files " +
                "are untouched.",
              System.currentTimeMillis()
            ),
            countUnread = false
          )
        }
        null -> {
          FableChatStore.append(
            appFilesDir,
            scopePath,
            FableChatMessage(
              FableRole.NOTICE,
              "Something went wrong while applying the change — nothing " +
                "was written.",
              System.currentTimeMillis()
            ),
            countUnread = false
          )
        }
      }
      renderChat()
    }
  }

  private fun onPatchDeny() {
    val scopePath = scopePath()
    FablePendingAi.denyProposal(scopePath)
    FableChatStore.append(
      filesDir,
      scopePath,
      FableChatMessage(
        FableRole.FABLE,
        "Okay, I left your files exactly as they are.",
        System.currentTimeMillis()
      ),
      countUnread = false
    )
    renderPatchCard()
  }

  /** Posts an AI chat/vision outcome into the chat, replacing the bubble. */
  private fun deliverAiOutcome(scopePath: String, outcome: FableAi.Outcome?) {
    val appFilesDir = filesDir
    val replyText: String = when (outcome) {
      is FableAi.Outcome.Reply -> {
        val proposal = outcome.proposal
        if (proposal != null && scopePath != HOME_SCOPE) {
          FablePendingAi.propose(proposal)
          outcome.text
        } else if (proposal != null) {
          outcome.text + "\n\nTo apply file changes, open the project " +
            "first — I can only edit files of an open project."
        } else {
          outcome.text
        }
      }
      is FableAi.Outcome.Failed -> outcome.message
      null -> "Something went wrong while asking Gemini. Nothing was changed."
    }
    val message = FableChatMessage(
      FableRole.FABLE, replyText, System.currentTimeMillis()
    )
    val replaced = FableChatStore.replacePending(
      appFilesDir, scopePath, FableAi.PENDING_MARKER, message
    )
    if (!replaced) {
      FableChatStore.append(appFilesDir, scopePath, message, countUnread = false)
    }
    renderChat()
  }

  private fun sendChatMessage() {
    val text = binding.fableHomeInput.text?.toString()?.trim().orEmpty()
    if (text.isEmpty()) return
    binding.fableHomeInput.text = null

    val scopePath = scopePath()
    // History for the AI is read BEFORE the new message is appended.
    val history = FableChatStore.load(filesDir, scopePath)
    FableChatStore.append(
      filesDir,
      scopePath,
      FableChatMessage(FableRole.USER, text, System.currentTimeMillis()),
      countUnread = false
    )

    val projectPath = try {
      IProjectManager.getInstance().projectDirPath
    } catch (e: Exception) {
      null
    }
    val index = if (projectPath.isNullOrBlank()) null
    else FableIndexStore.load(filesDir, projectPath)
    val brain = FableLocalBrain.reply(text, index, history.lastBuildFailed)
    if (brain.answeredLocally || !FableAi.isConfigured()) {
      FableChatStore.append(
        filesDir,
        scopePath,
        FableChatMessage(FableRole.FABLE, brain.text, System.currentTimeMillis()),
        countUnread = false
      )
      renderChat()
      return
    }

    FableChatStore.append(
      filesDir,
      scopePath,
      FableChatMessage(
        FableRole.FABLE, FableAi.PENDING_MARKER + " Asking Gemini…",
        System.currentTimeMillis()
      ),
      countUnread = false
    )
    renderChat()

    val appContext = applicationContext
    val appFilesDir = filesDir
    TaskExecutor.executeAsync(
      Callable {
        FableAi.chat(appContext, appFilesDir, scopePath, index, history.messages, text)
      }
    ) { outcome ->
      deliverAiOutcome(scopePath, outcome)
    }
  }

  private fun onAttachmentPicked(uri: Uri) {
    val scopePath = scopePath()
    val name = uri.lastPathSegment ?: "attachment"
    FableChatStore.append(
      filesDir,
      scopePath,
      FableChatMessage(FableRole.USER, "", System.currentTimeMillis(), attachmentName = name),
      countUnread = false
    )
    renderChat()

    val appContext = applicationContext
    val appFilesDir = filesDir

    // Images and videos go to Gemini vision when a key is set (plan §12).
    val mime = appContext.contentResolver.getType(uri) ?: ""
    if ((mime.startsWith("image/") || mime.startsWith("video/")) && FableAi.isConfigured()) {
      val log = FableChatStore.load(appFilesDir, scopePath)
      val question = log.messages.lastOrNull {
        it.role == FableRole.USER && it.text.isNotBlank()
      }?.text ?: ""
      val projectPath = try {
        IProjectManager.getInstance().projectDirPath
      } catch (e: Exception) {
        null
      }
      val index = if (projectPath.isNullOrBlank()) null
      else FableIndexStore.load(appFilesDir, projectPath)
      FableChatStore.append(
        appFilesDir,
        scopePath,
        FableChatMessage(
          FableRole.FABLE, FableAi.PENDING_MARKER + " Looking at that with Gemini…",
          System.currentTimeMillis()
        ),
        countUnread = false
      )
      renderChat()
      TaskExecutor.executeAsync(
        Callable {
          val images = FableArchiveReader.loadVisionImages(appContext, uri)
          FableAi.vision(appFilesDir, scopePath, index, images, question)
        }
      ) { outcome ->
        deliverAiOutcome(scopePath, outcome)
      }
      return
    }

    TaskExecutor.executeAsync(
      Callable {
        if (name.lowercase().endsWith(".zip") && FableIntake.looksLikeProjectZip(appContext, uri)) {
          val result = FableIntake.importZip(appContext, uri, name)
          result.projectDir to result.message
        } else {
          null to FableArchiveReader.summarize(appContext, uri)
        }
      }
    ) { outcome ->
      val message = outcome?.second ?: "I couldn't read that file."
      val importedDir = outcome?.first
      FableChatStore.append(
        appFilesDir,
        scopePath,
        FableChatMessage(FableRole.FABLE, message, System.currentTimeMillis()),
        countUnread = false
      )
      renderChat()
      if (importedDir != null) {
        IProjectManager.getInstance().openProject(importedDir)
        startActivity(Intent(this, EditorActivityKt::class.java))
      }
    }
  }

  companion object {
    const val HOME_SCOPE = "__home__"
  }
}
