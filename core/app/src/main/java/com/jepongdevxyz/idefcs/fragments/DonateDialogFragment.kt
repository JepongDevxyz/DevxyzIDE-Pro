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

package com.jepongdevxyz.idefcs.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jepongdevxyz.idefcs.databinding.LayoutDonateDialogBinding

/**
 * Bottom sheet shown when the user taps the Donate action on the home screen. Displays the
 * creator's InstaPay QR code so users can support DevxyzIDE Pro from any banking or e-wallet
 * app.
 */
class DonateDialogFragment : BottomSheetDialogFragment() {

  private var binding: LayoutDonateDialogBinding? = null

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    binding = LayoutDonateDialogBinding.inflate(inflater, container, false)
    return binding!!.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    binding!!.close.setOnClickListener { dismiss() }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    binding = null
  }

  companion object {
    const val TAG = "DonateDialogFragment"
  }
}
