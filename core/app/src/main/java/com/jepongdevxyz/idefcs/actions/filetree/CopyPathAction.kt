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

package com.jepongdevxyz.idefcs.actions.filetree

import android.content.Context
import com.blankj.utilcode.util.ClipboardUtils
import com.jepongdevxyz.idefcs.actions.ActionData
import com.jepongdevxyz.idefcs.actions.requireFile
import com.jepongdevxyz.idefcs.resources.R
import com.jepongdevxyz.idefcs.utils.flashSuccess

/**
 * Action to copy the absolute path of the selected file.
 *
 * @author Akash Yadav
 */
class CopyPathAction(context: Context, override val order: Int) :
  BaseFileTreeAction(context, labelRes = R.string.copy_path, iconRes = R.drawable.ic_copy) {

  override val id: String = "ide.editor.fileTree.copyPath"

  override suspend fun execAction(data: ActionData) {
    val file = data.requireFile()
    ClipboardUtils.copyText("[DevxyzIDE Pro] Copied File Path", file.absolutePath)
    flashSuccess(R.string.copied)
  }
}
