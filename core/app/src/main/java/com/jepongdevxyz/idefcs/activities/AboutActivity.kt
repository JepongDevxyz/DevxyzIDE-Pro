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

import android.content.Context
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.updatePaddingRelative
import com.blankj.utilcode.util.ClipboardUtils
import com.blankj.utilcode.util.SizeUtils
import com.jepongdevxyz.idefcs.R
import com.jepongdevxyz.idefcs.adapters.SimpleIconTitleDescriptionAdapter
import com.jepongdevxyz.idefcs.app.BaseApplication
import com.jepongdevxyz.idefcs.app.EdgeToEdgeIDEActivity
import com.jepongdevxyz.idefcs.app.configuration.IDEBuildConfigProvider
import com.jepongdevxyz.idefcs.buildinfo.BuildInfo
import com.jepongdevxyz.idefcs.databinding.ActivityAboutBinding
import com.jepongdevxyz.idefcs.models.IconTitleDescriptionItem
import com.jepongdevxyz.idefcs.models.SimpleIconTitleDescriptionItem
import com.jepongdevxyz.idefcs.utils.BuildInfoUtils
import com.jepongdevxyz.idefcs.utils.flashSuccess
import com.jepongdevxyz.idefcs.utils.resolveAttr

class AboutActivity : EdgeToEdgeIDEActivity() {

  private var _binding: ActivityAboutBinding? = null
  private val binding: ActivityAboutBinding
    get() = checkNotNull(_binding) {
      "Activity has been destroyed"
    }

  override fun bindLayout(): View {
    _binding = ActivityAboutBinding.inflate(layoutInflater)
    return _binding!!.root
  }

  companion object {

    private var id = 0
    private val ACTION_WEBSITE = id++
    private val ACTION_EMAIL = id++
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    binding.apply {

      setSupportActionBar(toolbar)
      supportActionBar!!.setDisplayHomeAsUpEnabled(true)
      supportActionBar!!.setTitle(R.string.about)
      toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

      aboutHeader.apply {
        ideVersion.text = createVersionText()
        ideVersion.isClickable = true
        ideVersion.isFocusable = true
        ideVersion.setBackgroundResource(R.drawable.bg_ripple)
        ideVersion.setOnClickListener {
          ClipboardUtils.copyText(BuildInfoUtils.getBuildInfoHeader())
          flashSuccess(R.string.copied)
        }
      }

      socials.apply {
        sectionTitle.setText(R.string.title_socials)
        sectionItems.adapter = AboutSocialItemsAdapter(createSocialItems(), ::handleActionClick)
      }
    }
  }

  override fun onApplySystemBarInsets(insets: Insets) {
    binding.toolbar.apply {
      setPaddingRelative(
        paddingStart + insets.left,
        paddingTop,
        paddingEnd + insets.right,
        paddingBottom
      )
    }
  }

  private fun handleActionClick(action: SimpleIconTitleDescriptionItem) {
    when (action.id) {
      ACTION_WEBSITE -> app.openWebsite()
      ACTION_EMAIL -> app.emailUs()
    }
  }

  private fun createSocialItems(): List<IconTitleDescriptionItem> {
    return mutableListOf<IconTitleDescriptionItem>().apply {
      add(
        createSimpleIconTextItem(
          this@AboutActivity,
          ACTION_WEBSITE,
          R.drawable.ic_website,
          R.string.about_option_website,
          BuildInfo.PROJECT_SITE
        )
      )
      add(
        createSimpleIconTextItem(
          this@AboutActivity,
          ACTION_EMAIL,
          R.drawable.ic_email,
          R.string.about_option_email,
          BaseApplication.EMAIL
        )
      )
    }
  }

  private fun createSimpleIconTextItem(
    context: Context,
    id: Int,
    @DrawableRes icon: Int,
    @StringRes title: Int,
    description: CharSequence
  ): SimpleIconTitleDescriptionItem {
    return SimpleIconTitleDescriptionItem(
      id,
      ContextCompat.getDrawable(context, icon),
      ContextCompat.getString(context, title),
      description
    )
  }

  /**
   * Create the version name string that should be displayed to the user.
   *
   * Format of the version name string is :
   *
   * `v[version-name]-[variant] (official)`
   */
  private fun createVersionText(): CharSequence {
    val builder = SpannableStringBuilder()
    builder.append("v")
    builder.append(getAppVersionName())
    builder.append("-")
    builder.append(IDEBuildConfigProvider.getInstance().cpuAbiName)
    builder.append(" (official)")
    return builder
  }

  private fun getAppVersionName(): String {
    return try {
      packageManager.getPackageInfo(packageName, 0).versionName ?: BuildInfo.VERSION_NAME
    } catch (e: Exception) {
      BuildInfo.VERSION_NAME
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    _binding = null
  }

  class AboutSocialItemsAdapter(
    items: List<IconTitleDescriptionItem>,
    private val onClickListener: (SimpleIconTitleDescriptionItem) -> Unit
  ) : SimpleIconTitleDescriptionAdapter(items) {

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
      super.onBindViewHolder(holder, position)
      val binding = holder.binding
      val item = getItem(position) as SimpleIconTitleDescriptionItem
      val dp8 = SizeUtils.dp2px(8f)
      binding.icon.updatePaddingRelative(dp8, dp8, dp8, dp8)
      binding.title.setTextAppearance(R.style.TextAppearance_Material3_TitleSmall)

      binding.description.maxLines = 3
      binding.description.setTextAppearance(R.style.TextAppearance_Material3_BodySmall)
      binding.description.setTextColor(binding.description.context.resolveAttr(R.attr.colorPrimary))

      binding.root.isClickable = true
      binding.root.isFocusable = true
      binding.root.setBackgroundResource(R.drawable.bg_ripple)
      binding.root.setOnClickListener {
        onClickListener(item)
      }
    }
  }
}
