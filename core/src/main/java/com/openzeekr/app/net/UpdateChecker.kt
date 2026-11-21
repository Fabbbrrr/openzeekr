package com.openzeekr.app.net

import com.openzeekr.app.util.Logx
import com.openzeekr.core.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** A published GitHub release of OpenZeekr. */
data class ReleaseInfo(val version: String, val tag: String, val url: String, val notes: String)

/**
 * Checks the GitHub Releases API for a newer OpenZeekr build. The repo is public, so no auth is
 * needed (the unauthenticated GitHub API allows 60 requests/hour, plenty for an occasional check).
 * Uses the JSON tree API (no serializer), so it needs no extra R8 keep rules.
 */
object UpdateChecker {
    // FORK-ONLY: repo + release marker come from gradle.properties via BuildConfig (see
    // core/build.gradle.kts and FORK-BUILD.md). With the fork properties set, only releases whose
    // tag carries the marker are offered, so an upstream release can never be pushed onto a
    // fork-installed app; the marker segment sorts as a build tier above its base version.
    private val repo: String get() = BuildConfig.UPDATE_REPO.ifBlank { "borconi/openzeekr" }
    private val marker: String get() = BuildConfig.UPDATE_MARKER
    private val LATEST_URL: String get() = "https://api.github.com/repos/$repo/releases/latest"
    private val RELEASES_PAGE: String get() = "https://github.com/$repo/releases/latest"
    private val client by lazy { OkHttpClient() }
    private val json = Json { ignoreUnknownKeys = true }

    /** Fetch the latest published release, or null on any error / non-2xx. */
    fun fetchLatest(): ReleaseInfo? = runCatching {
        val req = Request.Builder().url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "openzeekr-app")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { Logx.d("update", "GitHub releases HTTP ${resp.code}"); return null }
            val o = json.parseToJsonElement(resp.body?.string() ?: return null).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            // FORK-ONLY: channel rule — while an update marker is configured, a release without it
            // is not ours to offer (mirrors the fork-version gate on the comparison side).
            if (marker.isNotBlank() && !tag.contains(marker, ignoreCase = true)) {
                Logx.d("update", "latest release $tag lacks the $marker marker - not offering")
                return null
            }
            ReleaseInfo(
                version = tag.trimStart('v', 'V'),
                tag = tag,
                url = o["html_url"]?.jsonPrimitive?.contentOrNull ?: RELEASES_PAGE,
                notes = o["body"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }.getOrElse { Logx.d("update", "update check failed: ${it.message}"); null }

    /**
     * True if [latest] is a strictly higher semantic version than [installed] (both like "0.1.4").
     * Ignores a "v" prefix and any pre-release / build tail (e.g. "0.2.0-wearos" -> 0.2.0).
     */
    fun isNewer(installed: String, latest: String): Boolean {
        // FORK-ONLY: an optional "fab<sep><n>" segment (e.g. "0.2.7-fab.1") is a build tier that
        // sorts above its base version; a version without it is tier -1. Base comparison unchanged.
        fun tier(v: String): Pair<List<Int>, Int> {
            val s = v.trimStart('v', 'V')
            val fab = Regex("""fab[.\-]?(\d+)""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            return s.split('-', '+')[0].split('.').map { it.toIntOrNull() ?: 0 } to fab
        }
        val (a, af) = tier(installed); val (b, bf) = tier(latest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        // Base versions equal (e.g. installed 0.2.7 vs published 0.2.7-fab.1): the marker tier decides.
        return bf > af
    }
}
