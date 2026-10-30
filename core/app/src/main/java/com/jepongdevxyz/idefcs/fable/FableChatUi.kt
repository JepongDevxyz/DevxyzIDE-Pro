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

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.jepongdevxyz.idefcs.resources.R

/**
 * Shared bubble rendering for Fable chat surfaces (the sidebar panel and the
 * home-screen chat). Bubbles use theme colors only, so dark/light mode stays
 * in sync: user messages align to the end, Fable's to the start, and build
 * notices use the error container color so they stand out.
 */
object FableChatUi {

  fun renderMessages(container: LinearLayout, log: FableChatLog) {
    container.removeAllViews()
    for (message in log.messages) {
      container.addView(bubble(container.context, message))
    }
  }

  fun bubble(context: Context, message: FableChatMessage): TextView {
    val density = context.resources.displayMetrics.density
    fun dp(value: Int): Int = (value * density).toInt()

    val (backgroundRes, colorAttr, gravity) = when (message.role) {
      FableRole.USER -> Triple(
        R.drawable.bg_fable_bubble_user,
        com.google.android.material.R.attr.colorOnPrimaryContainer,
        Gravity.END
      )
      FableRole.NOTICE -> Triple(
        R.drawable.bg_fable_bubble_notice,
        com.google.android.material.R.attr.colorOnErrorContainer,
        Gravity.START
      )
      FableRole.FABLE -> Triple(
        R.drawable.bg_fable_bubble_fable,
        com.google.android.material.R.attr.colorOnSurface,
        Gravity.START
      )
    }

    val text = buildString {
      if (message.attachmentName != null) {
        append("📎 ").append(message.attachmentName)
        if (message.text.isNotEmpty()) append('\n')
      }
      append(message.text)
    }

    return TextView(context).apply {
      this.text = text
      setBackgroundResource(backgroundRes)
      setTextColor(MaterialColors.getColor(this, colorAttr))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setPadding(dp(12), dp(8), dp(12), dp(8))
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply {
        this.gravity = gravity
        topMargin = dp(8)
        marginStart = if (gravity == Gravity.END) dp(48) else 0
        marginEnd = if (gravity == Gravity.START) dp(48) else 0
      }
    }
  }
}
