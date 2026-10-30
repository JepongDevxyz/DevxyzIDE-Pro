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
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.graphics.Insets
import androidx.core.view.marginBottom
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMarginsRelative
import androidx.core.view.updatePadding
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.jepongdevxyz.idefcs.databinding.FragmentEditorSidebarBinding
import com.jepongdevxyz.idefcs.fragments.FragmentWithBinding
import com.jepongdevxyz.idefcs.utils.EditorSidebarActions

/**
 * Fragment for showing the default items in the editor activity's sidebar.
 *
 * @author Akash Yadav
 */
class EditorSidebarFragment : FragmentWithBinding<FragmentEditorSidebarBinding>(
  FragmentEditorSidebarBinding::inflate
) {

  internal fun onApplyWindowInsets(insets: Insets) {
    _binding?.apply {
      title.updateLayoutParams<MarginLayoutParams> {
        updateMarginsRelative(
          top = title.marginTop + insets.top,
        )
      }
      fragmentContainer.updateLayoutParams<MarginLayoutParams> {
        updateMarginsRelative(
          bottom = fragmentContainer.marginBottom + insets.bottom,
        )
      }
      navigation.updatePadding(
        top = navigation.paddingTop + insets.top,
        bottom = navigation.paddingBottom + insets.bottom,
        left = navigation.paddingLeft + insets.left,
      )
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    EditorSidebarActions.setup(this)
  }

  /**
   * Navigates the sidebar's NavHost to the destination with the given
   * action/destination id, using the same options as the navigation rail.
   * Used by the Fable chat head to open the Fable panel.
   */
  internal fun openDestination(destinationId: String) {
    try {
      val binding = _binding ?: return
      val controller = binding.fragmentContainer.getFragment<NavHostFragment>().navController
      controller.navigate(destinationId, navOptions {
        launchSingleTop = true
        restoreState = true
      })
    } catch (e: Exception) {
      // The destination may already be current, or the graph may have
      // changed; opening the drawer is still a useful outcome.
    }
  }

  /**
   * Get the (nullable) binding object for this fragment.
   */
  internal fun getBinding() = _binding
}