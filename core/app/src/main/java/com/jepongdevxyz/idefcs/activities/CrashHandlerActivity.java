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

package com.jepongdevxyz.idefcs.activities;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.jepongdevxyz.idefcs.app.IDEActivity;
import com.jepongdevxyz.idefcs.databinding.ActivityCrashHandlerBinding;
import com.jepongdevxyz.idefcs.fragments.CrashReportFragment;

public class CrashHandlerActivity extends IDEActivity {

  public static final String REPORT_ACTION = "com.jepongdevxyz.pro.REPORT_CRASH";
  public static final String TRACE_KEY = "crash_trace";
  private ActivityCrashHandlerBinding binding;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final var extra = getIntent().getExtras();
    if (extra == null) {
      finishAffinity();
      return;
    }

    final var report = extra.getString(TRACE_KEY, "Unable to get logs.");
    final var fragment = CrashReportFragment.newInstance(report);

    getSupportFragmentManager()
        .beginTransaction()
        .replace(binding.getRoot().getId(), fragment, "crash_report_fragment")
        .addToBackStack(null)
        .commit();
  }

  @Override
  @NonNull
  protected View bindLayout() {
    binding = ActivityCrashHandlerBinding.inflate(getLayoutInflater());
    return binding.getRoot();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    binding = null;
  }
}
