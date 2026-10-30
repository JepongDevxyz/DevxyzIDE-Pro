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

package com.jepongdevxyz.idefcs.models

import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.jepongdevxyz.idefcs.resources.R
import java.util.Collections

/**
 * An action button shown on the main screen.
 *
 * @author Akash Yadav
 */
data class MainScreenAction
@JvmOverloads
constructor(
  val id: Int,
  @StringRes val text: Int,
  @DrawableRes val icon: Int,
  var onClick: ((MainScreenAction, View) -> Unit)? = null,
  var onLongClick: ((MainScreenAction, View) -> Boolean)? = null
) {

  companion object {

    const val ACTION_CREATE_PROJECT = 0
    const val ACTION_OPEN_PROJECT = 1
    const val ACTION_CLONE_REPO = 2
    const val ACTION_OPEN_TERMINAL = 3
    const val ACTION_PREFERENCES = 4
    const val ACTION_DONATE = 5
    const val ACTION_DOCS = 6
    const val ACTION_FABLE = 7

    /**
     * Get all main screen actions.
     */
    @JvmStatic
    fun all(): List<MainScreenAction> {
      return mutableListOf<MainScreenAction>().apply {
        val createProject = MainScreenAction(
          ACTION_CREATE_PROJECT,
          R.string.create_project,
          R.drawable.ic_add)

        val openProject = MainScreenAction(
          ACTION_OPEN_PROJECT,
          R.string.msg_open_existing_project,
          R.drawable.ic_folder)

        val cloneGitRepository = MainScreenAction(
          ACTION_CLONE_REPO,
          R.string.git_clone_repo,
          R.drawable.ic_git)

        val openTerminal = MainScreenAction(
          ACTION_OPEN_TERMINAL,
          R.string.title_terminal,
          R.drawable.ic_terminal)

        val preferences = MainScreenAction(
          ACTION_PREFERENCES,
          R.string.msg_preferences,
          R.drawable.ic_settings)

        val donate = MainScreenAction(
          ACTION_DONATE,
          R.string.btn_donate,
          R.drawable.ic_heart
        )

        val docs = MainScreenAction(
          ACTION_DOCS,
          R.string.btn_docs,
          R.drawable.ic_docs)

        val fable = MainScreenAction(
          ACTION_FABLE,
          R.string.title_fable_panel,
          R.drawable.ic_fable)

        Collections.addAll(this,
          createProject,
          fable,
          openProject,
          cloneGitRepository,
          openTerminal,
          preferences,
          donate,
          docs
        )
      }
    }
  }
}
