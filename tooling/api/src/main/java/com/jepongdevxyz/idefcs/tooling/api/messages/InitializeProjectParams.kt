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

package com.jepongdevxyz.idefcs.tooling.api.messages

import java.io.Serializable

/**
 * Message sent from client to server to initialize the tooling API client in the given directory.
 *
 * @property directory The absolute path to the root directory of the project to initialize.
 * @property gradleDistribution The parameters for the Gradle distribution to use.
 * @property androidParams The [AndroidInitializationParams] for initializing the Android module projects.
 * @property forceNewConnection When true, the server must drop its current Gradle
 *           connection and connect again even if the other params are unchanged.
 *           A fresh connection spawns a fresh Gradle daemon, which rescans the
 *           Android SDK — needed after an SDK platform is installed in-app,
 *           because a long-lived daemon keeps its stale SDK view.
 * @author Akash Yadav
 */
data class InitializeProjectParams @JvmOverloads constructor(
  val directory: String,
  val gradleDistribution: GradleDistributionParams = GradleDistributionParams.WRAPPER,
  val androidParams: AndroidInitializationParams = AndroidInitializationParams.DEFAULT,
  val forceNewConnection: Boolean = false
) : Serializable

