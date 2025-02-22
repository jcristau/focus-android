/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.experiments

import android.content.Context
import android.net.Uri
import mozilla.components.service.nimbus.Nimbus
import mozilla.components.service.nimbus.NimbusApi
import mozilla.components.service.nimbus.NimbusAppInfo
import mozilla.components.service.nimbus.NimbusDisabled
import mozilla.components.service.nimbus.NimbusServerSettings
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.focus.BuildConfig
import org.mozilla.focus.R
import org.mozilla.focus.ext.components
import org.mozilla.focus.ext.settings

@Suppress("TooGenericExceptionCaught")
fun createNimbus(context: Context, url: String?): NimbusApi =
    try {
        val crashReporter = context.components.crashReporter
        // Eventually we'll want to use `NimbusDisabled` when we have no NIMBUS_ENDPOINT.
        // but we keep this here to not mix feature flags and how we configure Nimbus.
        val serverSettings = if (!url.isNullOrBlank()) {
            if (context.settings.shouldUseNimbusPreview) {
                NimbusServerSettings(url = Uri.parse(url), collection = "nimbus-preview")
            } else {
                NimbusServerSettings(url = Uri.parse(url))
            }
        } else {
            null
        }

        // Global opt out state is stored in Nimbus, and shouldn't be toggled to `true`
        // from the app unless the user does so from a UI control.
        // However, the user may have opt-ed out of making experiments already, so
        // we should respect that setting here.
        val enabled = context.settings.isExperimentationEnabled

        // The name "focus-android" or "klar-android" here corresponds to the app_name defined
        // for the family of apps that encompasses all of the channels for the Focus app.
        // This is defined upstream in the telemetry system. For more context on where the
        // app_name come from see:
        // https://probeinfo.telemetry.mozilla.org/v2/glean/app-listings
        // and
        // https://github.com/mozilla/probe-scraper/blob/master/repositories.yaml
        val appInfo = NimbusAppInfo(
            appName = getNimbusAppName(),
            // Note: Using BuildConfig.BUILD_TYPE is important here so that it matches the value
            // passed into Glean. `Config.channel.toString()` turned out to be non-deterministic
            // and would mostly produce the value `Beta` and rarely would produce `beta`.
            channel = BuildConfig.BUILD_TYPE
        )
        Nimbus(
            context,
            appInfo,
            serverSettings,
            { message, throwable ->
                crashReporter.submitCaughtException(throwable)
                Logger.error("Nimbus error: $message", throwable)
            }
        ).apply {
            // This performs the minimal amount of work required to load branch and enrolment data
            // into memory. If `getExperimentBranch` is called from another thread between here
            // and the next nimbus disk write (setting `globalUserParticipation` or
            // `applyPendingExperiments()`) then this has you covered.
            // This call does its work on the db thread.
            initialize()

            if (!enabled) {
                // This opts out of nimbus experiments. It involves writing to disk, so does its
                // work on the db thread.
                globalUserParticipation = enabled
            }

            if (url.isNullOrBlank()) {
                setExperimentsLocally(R.raw.initial_experiments)
            }

            // We may have downloaded experiments on a previous run, so let's start using them
            // now. We didn't do this earlier, so as to make getExperimentBranch and friends returns
            // the same thing throughout the session. This call does its work on the db thread.
            applyPendingExperiments()

            // Now fetch the experiments from the server. These will be available for feature
            // configuration on the next run of the app. This call launches on the fetch thread.
            fetchExperiments()
        }
    } catch (e: Throwable) {
        // Something went wrong. We'd like not to, but stability of the app is more important than
        // failing fast here.
        val crashReporter = context.components.crashReporter
        if (crashReporter.enabled) {
            crashReporter.submitCaughtException(e)
        } else {
            Logger.error("Failed to initialize Nimbus", e)
        }
        NimbusDisabled(context = context)
    }

fun getNimbusAppName(): String {
    return if (BuildConfig.FLAVOR.contains("focus")) {
        "focus_android"
    } else {
        "klar_android"
    }
}
