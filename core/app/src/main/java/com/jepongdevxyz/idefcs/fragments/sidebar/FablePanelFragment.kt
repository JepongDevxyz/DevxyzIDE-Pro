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

package com.jepongdevxyz.idefcs.fragments.sidebar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.jepongdevxyz.idefcs.activities.editor.EditorActivityKt
import com.jepongdevxyz.idefcs.databinding.FragmentFablePanelBinding
import com.jepongdevxyz.idefcs.fable.FableArchiveReader
import com.jepongdevxyz.idefcs.fable.FableBuildWatcher
import com.jepongdevxyz.idefcs.fable.FableChatMessage
import com.jepongdevxyz.idefcs.fable.FableChatStore
import com.jepongdevxyz.idefcs.fable.FableChatUi
import com.jepongdevxyz.idefcs.fable.FableIndexStore
import com.jepongdevxyz.idefcs.fable.FableIntake
import com.jepongdevxyz.idefcs.fable.FableLocalBrain
import com.jepongdevxyz.idefcs.fable.FablePendingInstall
import com.jepongdevxyz.idefcs.fable.FableRole
import com.jepongdevxyz.idefcs.fable.KnowledgeIndex
import com.jepongdevxyz.idefcs.fable.KnowledgeIndexBuilder
import com.jepongdevxyz.idefcs.fragments.FragmentWithBinding
import com.jepongdevxyz.idefcs.preferences.internal.GeneralPreferences
import com.jepongdevxyz.idefcs.projects.IProjectManager
import com.jepongdevxyz.idefcs.resources.R
import com.jepongdevxyz.idefcs.tasks.TaskExecutor
import com.jepongdevxyz.idefcs.utils.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable

/**
 * The Devxyz Fable panel. Phase 1 built the read-only project map; Phase 2
 * adds the local chat (deterministic answers from the map, attachment
 * reading, project ZIP intake) and renders the build watcher's proactive
 * notices. Fable is OFF by default; when off, nothing here runs.
 */
class FablePanelFragment : FragmentWithBinding<FragmentFablePanelBinding>(
  FragmentFablePanelBinding::inflate
) {

  private val pendingListener: () -> Unit = {
    _binding?.root?.post { renderActionCard() }
  }

  private val chatListener: () -> Unit = {
    _binding?.root?.post { renderChat() }
  }

  private val attachmentLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        onAttachmentPicked(uri)
      }
    }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    binding.fableSwitch.isChecked = GeneralPreferences.fableEnabled
    binding.fableSwitch.setOnCheckedChangeListener { _, checked ->
      GeneralPreferences.fableEnabled = checked
      render()
    }
    binding.fableRefresh.setOnClickListener { rebuildIndex() }
    binding.fableChatSend.setOnClickListener { sendChatMessage() }
    binding.fableActionAllow.setOnClickListener { onInstallCardAllow() }
    binding.fableActionDeny.setOnClickListener { onInstallCardDeny() }
    binding.fableChatAttach.setOnClickListener {
      attachmentLauncher.launch(arrayOf("*/*"))
    }

    FableChatStore.addListener(chatListener)
    FablePendingInstall.addListener(pendingListener)
    render()
  }

  override fun onDestroyView() {
    FableChatStore.removeListener(chatListener)
    FablePendingInstall.removeListener(pendingListener)
    super.onDestroyView()
  }

  private fun render() {
    val binding = _binding ?: return
    renderActionCard()
    val enabled = GeneralPreferences.fableEnabled
    binding.fableSwitch.isChecked = enabled
    binding.fableRefresh.isEnabled = enabled

    if (!enabled) {
      binding.fableStatus.text = ""
      binding.fableMapBody.text = getString(R.string.msg_fable_off)
      binding.fableEnvBody.text = ""
      binding.fableUpdated.text = ""
      renderChat()
      return
    }

    val projectDir = currentProjectDir()
    if (projectDir == null) {
      binding.fableStatus.text = ""
      binding.fableMapBody.text = getString(R.string.msg_fable_no_project)
      binding.fableEnvBody.text = ""
      binding.fableUpdated.text = ""
      renderChat()
      return
    }

    val cached = FableIndexStore.load(requireContext().filesDir, projectDir.absolutePath)
    if (cached != null) {
      showIndex(cached)
    } else {
      rebuildIndex()
    }
    renderChat()
  }

  private fun rebuildIndex() {
    val binding = _binding ?: return
    if (!GeneralPreferences.fableEnabled) {
      return
    }

    val projectDir = currentProjectDir()
    if (projectDir == null) {
      render()
      return
    }

    binding.fableStatus.text = getString(R.string.msg_fable_indexing)
    val filesDir = requireContext().filesDir
    TaskExecutor.executeAsync(
      Callable {
        val index = KnowledgeIndexBuilder.build(projectDir, Environment.ANDROID_HOME, Environment.JAVA_HOME)
        FableIndexStore.save(filesDir, index)
        index
      }
    ) { result ->
      if (result != null) {
        showIndex(result)
      }
    }
  }

  private fun showIndex(index: KnowledgeIndex) {
    val binding = _binding ?: return

    binding.fableStatus.text = index.projectName

    val map = StringBuilder()
    map.append(index.projectName).append('\n')
    map.append("Modules: ").append(index.modules.size)
      .append(" · Files: ").append(index.totalFileCount)
      .append(" · Dependencies: ").append(index.dependencyCount)
      .append('\n')
    map.append("Gradle ").append(index.gradleVersion ?: "—")
      .append(" · AGP ").append(index.agpVersion ?: "—")
      .append(" · Kotlin ").append(index.kotlinVersion ?: "—")
    for (module in index.modules) {
      map.append('\n').append('\n')
      map.append(module.name).append('\n')
      map.append("compileSdk ").append(module.compileSdk?.toString() ?: "—")
        .append(" · minSdk ").append(module.minSdk?.toString() ?: "—")
        .append(" · targetSdk ").append(module.targetSdk?.toString() ?: "—")
        .append('\n')
      map.append(module.dependencies.size).append(" dependencies · ")
        .append(module.sourceFileCount).append(" source files")
    }
    binding.fableMapBody.text = map.toString()

    val env = StringBuilder()
    env.append("SDK platforms: ")
      .append(if (index.sdkPlatforms.isEmpty()) "—" else index.sdkPlatforms.joinToString(", "))
      .append('\n')
    env.append("Build tools: ")
      .append(if (index.buildToolsVersions.isEmpty()) "—" else index.buildToolsVersions.joinToString(", "))
      .append('\n')
    env.append("Java: ").append(index.javaVersion ?: "—")
    binding.fableEnvBody.text = env.toString()

    val updated = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
      .format(Date(index.builtAtMillis))
    binding.fableUpdated.text = getString(R.string.msg_fable_updated, updated)
  }

  // ------------------------------------------------------- approval card

  /** Shows the Allow/Deny install card while a request is pending. */
  private fun renderActionCard() {
    val binding = _binding ?: return
    val projectDir = if (GeneralPreferences.fableEnabled) currentProjectDir() else null
    val api = projectDir?.let { FablePendingInstall.pendingApi(it.absolutePath) }
    if (api == null) {
      binding.fableActionCard.visibility = View.GONE
      return
    }
    binding.fableActionTitle.text =
      getString(R.string.msg_fable_install_card_title, api)
    binding.fableActionBody.text =
      getString(R.string.msg_fable_install_card_body, api)
    binding.fableActionCard.visibility = View.VISIBLE
  }

  private fun onInstallCardAllow() {
    val projectDir = currentProjectDir() ?: return
    val projectPath = projectDir.absolutePath
    val api = FablePendingInstall.consumeAllowed(projectPath) ?: run {
      renderActionCard()
      return
    }
    renderActionCard()
    FableBuildWatcher.runApprovedInstall(
      requireContext().filesDir, projectPath, api)
  }

  private fun onInstallCardDeny() {
    val projectDir = currentProjectDir() ?: return
    val projectPath = projectDir.absolutePath
    FablePendingInstall.deny(projectPath)
    FableChatStore.append(
      requireContext().filesDir,
      projectPath,
      FableChatMessage(
        FableRole.FABLE,
        "Sige, hindi ko i-install ang platform. Lalabas ulit ang card " +
          "sa susunod na session kung kailangan pa rin ito ng build.",
        System.currentTimeMillis()
      ),
      countUnread = false
    )
    renderActionCard()
  }

  // ------------------------------------------------------------------ chat

  private fun renderChat() {
    val binding = _binding ?: return
    val projectDir = currentProjectDir()

    if (!GeneralPreferences.fableEnabled || projectDir == null) {
      binding.fableChatList.removeAllViews()
      binding.fableChatEmpty.visibility = View.VISIBLE
      binding.fableChatEmpty.text = getString(
        if (!GeneralPreferences.fableEnabled) R.string.msg_fable_chat_off
        else R.string.msg_fable_no_project
      )
      return
    }

    val log = FableChatStore.load(requireContext().filesDir, projectDir.absolutePath)
    FableChatUi.renderMessages(binding.fableChatList, log)
    binding.fableChatEmpty.visibility = if (log.messages.isEmpty()) View.VISIBLE else View.GONE
    binding.fableChatEmpty.text = getString(R.string.msg_fable_chat_empty)
    if (isPanelOnScreen()) {
      FableChatStore.markAllRead(requireContext().filesDir, projectDir.absolutePath)
    }
  }

  /**
   * The panel lives inside the editor's start drawer: its view exists (and
   * re-renders on every chat change) even while the drawer is closed and the
   * user cannot see it. Marking messages read in that state kills the chat
   * head's badge the instant a message arrives, so only mark read while the
   * drawer is genuinely open.
   */
  private fun isPanelOnScreen(): Boolean {
    var v: View? = view
    while (v != null) {
      if (v is androidx.drawerlayout.widget.DrawerLayout) {
        return v.isDrawerOpen(androidx.core.view.GravityCompat.START)
      }
      v = v.parent as? View
    }
    return false
  }

  private fun sendChatMessage() {
    val binding = _binding ?: return
    val projectDir = currentProjectDir() ?: return
    val text = binding.fableChatInput.text?.toString()?.trim().orEmpty()
    if (text.isEmpty()) return
    binding.fableChatInput.text = null

    val filesDir = requireContext().filesDir
    val projectPath = projectDir.absolutePath
    FableChatStore.append(
      filesDir,
      projectPath,
      FableChatMessage(FableRole.USER, text, System.currentTimeMillis()),
      countUnread = false
    )

    val index = FableIndexStore.load(filesDir, projectPath)
    val log = FableChatStore.load(filesDir, projectPath)
    val reply = FableLocalBrain.reply(text, index, log.lastBuildFailed)
    FableChatStore.append(
      filesDir,
      projectPath,
      FableChatMessage(FableRole.FABLE, reply, System.currentTimeMillis()),
      countUnread = false
    )
    renderChat()
  }

  private fun onAttachmentPicked(uri: Uri) {
    val projectDir = currentProjectDir() ?: return
    val context = requireContext()
    val filesDir = context.filesDir
    val projectPath = projectDir.absolutePath
    val name = uri.lastPathSegment ?: "attachment"

    FableChatStore.append(
      filesDir,
      projectPath,
      FableChatMessage(FableRole.USER, "", System.currentTimeMillis(), attachmentName = name),
      countUnread = false
    )
    renderChat()

    val appContext = context.applicationContext
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
      val message = outcome?.second ?: "Hindi ko nabasa ang file na iyon."
      val importedDir = outcome?.first
      FableChatStore.append(
        filesDir,
        projectPath,
        FableChatMessage(FableRole.FABLE, message, System.currentTimeMillis()),
        countUnread = false
      )
      renderChat()
      if (importedDir != null) {
        IProjectManager.getInstance().openProject(importedDir)
        startActivity(Intent(requireContext(), EditorActivityKt::class.java))
      }
    }
  }

  private fun currentProjectDir(): File? {
    val path = try {
      IProjectManager.getInstance().projectDirPath
    } catch (e: Exception) {
      null
    }
    if (path.isNullOrBlank()) {
      return null
    }
    val dir = File(path)
    return if (dir.isDirectory) dir else null
  }
}
