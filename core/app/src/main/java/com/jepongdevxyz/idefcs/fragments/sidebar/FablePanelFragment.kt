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

import android.os.Bundle
import android.view.View
import com.jepongdevxyz.idefcs.databinding.FragmentFablePanelBinding
import com.jepongdevxyz.idefcs.fable.FableIndexStore
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
 * The Devxyz Fable panel (Phase 1: read-only). Shows Fable's local knowledge
 * index of the opened project. Fable is OFF by default; when off, nothing is
 * indexed. Everything is computed and stored on-device.
 */
class FablePanelFragment : FragmentWithBinding<FragmentFablePanelBinding>(
  FragmentFablePanelBinding::inflate
) {

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    binding.fableSwitch.isChecked = GeneralPreferences.fableEnabled
    binding.fableSwitch.setOnCheckedChangeListener { _, checked ->
      GeneralPreferences.fableEnabled = checked
      render()
    }
    binding.fableRefresh.setOnClickListener { rebuildIndex() }

    render()
  }

  private fun render() {
    val binding = _binding ?: return
    val enabled = GeneralPreferences.fableEnabled
    binding.fableSwitch.isChecked = enabled
    binding.fableRefresh.isEnabled = enabled

    if (!enabled) {
      binding.fableStatus.text = ""
      binding.fableMapBody.text = getString(R.string.msg_fable_off)
      binding.fableEnvBody.text = ""
      binding.fableUpdated.text = ""
      return
    }

    val projectDir = currentProjectDir()
    if (projectDir == null) {
      binding.fableStatus.text = ""
      binding.fableMapBody.text = getString(R.string.msg_fable_no_project)
      binding.fableEnvBody.text = ""
      binding.fableUpdated.text = ""
      return
    }

    val cached = FableIndexStore.load(requireContext().filesDir, projectDir.absolutePath)
    if (cached != null) {
      showIndex(cached)
    } else {
      rebuildIndex()
    }
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

  private fun currentProjectDir(): File? {
    val path = IProjectManager.getInstance().projectDirPath
    if (path.isNullOrBlank()) {
      return null
    }
    val dir = File(path)
    return if (dir.isDirectory) dir else null
  }
}
