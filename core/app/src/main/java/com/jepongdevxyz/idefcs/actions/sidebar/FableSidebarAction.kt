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

package com.jepongdevxyz.idefcs.actions.sidebar

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.jepongdevxyz.idefcs.R
import com.jepongdevxyz.idefcs.fragments.sidebar.FablePanelFragment
import kotlin.reflect.KClass

class FableSidebarAction(context: Context, override val order: Int) : AbstractSidebarAction() {

  companion object {
    const val ID = "ide.editor.sidebar.fable"
  }

  override val id: String = ID
  override val fragmentClass: KClass<out Fragment> = FablePanelFragment::class

  init {
    label = context.getString(R.string.title_fable_panel)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_fable)
  }
}
