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
import com.jepongdevxyz.idefcs.fable.FableArchiveReader
import com.jepongdevxyz.idefcs.fable.FableChatMessage
import com.jepongdevxyz.idefcs.fable.FableChatStore
import com.jepongdevxyz.idefcs.fable.FableChatUi
import com.jepongdevxyz.idefcs.fable.FableIndexStore
import com.jepongdevxyz.idefcs.fable.FableIntake
import com.jepongdevxyz.idefcs.fable.FableLocalBrain
import com.jepongdevxyz.idefcs.fable.FableRole
import com.jepongdevxyz.idefcs.preferences.internal.GeneralPreferences
import com.jepongdevxyz.idefcs.projects.IProjectManager
import com.jepongdevxyz.idefcs.resources.R
import com.jepongdevxyz.idefcs.tasks.TaskExecutor
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

    FableChatStore.addListener(chatListener)
    renderChat()
  }

  override fun onDestroy() {
    FableChatStore.removeListener(chatListener)
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
    binding.fableHomeScroll.post { binding.fableHomeScroll.fullScroll(View.FOCUS_DOWN) }
  }

  private fun sendChatMessage() {
    val text = binding.fableHomeInput.text?.toString()?.trim().orEmpty()
    if (text.isEmpty()) return
    binding.fableHomeInput.text = null

    val scopePath = scopePath()
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
    val log = FableChatStore.load(filesDir, scopePath)
    val reply = FableLocalBrain.reply(text, index, log.lastBuildFailed)
    FableChatStore.append(
      filesDir,
      scopePath,
      FableChatMessage(FableRole.FABLE, reply, System.currentTimeMillis()),
      countUnread = false
    )
    renderChat()
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
