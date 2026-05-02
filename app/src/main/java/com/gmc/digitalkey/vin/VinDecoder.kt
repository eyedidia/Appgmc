package com.gmc.digitalkey.vin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VinInfo(
    val make: String,
    val model: String,
    val year: Int,
    val bodyClass: String,
    val isElectric: Boolean,
    val imageUrl: String = ""
)

object VinDecoder {

    // US NHTSA public API — no key, no rate limit specified, HTTPS
    private const val NHTSA = "https://vpic.nhtsa.dot.gov/api/vehicles/decodevinvalues/%s?format=json"

    // Wikipedia REST summary — returns thumbnail.source for main article image
    private const val WIKI = "https://en.wikipedia.org/api/rest_v1/page/summary/%s"

    // Known GMC EV Wikipedia slugs
    private val wikiSlug = mapOf(
        "HUMMER EV PICKUP" to "GMC_Hummer_EV",
        "HUMMER EV SUV"    to "GMC_Hummer_EV_SUV",
        "SIERRA EV"        to "GMC_Sierra_EV",
        "TERRAIN EV"       to "GMC_Terrain_(fourth_generation)"
    )

    suspend fun decode(vin: String): VinInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = get(NHTSA.format(vin.uppercase()))
            val result = JSONObject(raw).getJSONArray("Results").getJSONObject(0)
            val make      = result.getString("Make").uppercase()
            val model     = result.getString("Model").uppercase()
            val yearStr   = result.getString("ModelYear")
            val bodyClass = result.getString("BodyClass")
            val fuel      = result.getString("FuelTypePrimary")
            val year      = yearStr.toIntOrNull() ?: 0
            val isElectric = fuel.contains("electric", ignoreCase = true)

            // Fetch Wikipedia thumbnail
            val slug = resolveWikiSlug(make, model, bodyClass)
            val imageUrl = if (slug != null) fetchWikiImage(slug) else ""

            VinInfo(make, model, year, bodyClass, isElectric, imageUrl)
        }.getOrNull()
    }

    private fun resolveWikiSlug(make: String, model: String, bodyClass: String): String? {
        if (make != "GMC") return null
        // Try exact keys first
        wikiSlug.forEach { (key, slug) ->
            if (model.contains(key.substringBefore(" EV"), ignoreCase = true) &&
                (key.contains("PICKUP") && bodyClass.contains("pickup", ignoreCase = true) ||
                 key.contains("SUV")    && bodyClass.contains("utility", ignoreCase = true) ||
                 (!key.contains("PICKUP") && !key.contains("SUV")))) {
                return slug
            }
        }
        // Fallback: best-effort match
        return wikiSlug.entries.firstOrNull { (k, _) ->
            model.contains(k.substringBefore(" EV"), ignoreCase = true)
        }?.value
    }

    private fun fetchWikiImage(slug: String): String {
        return runCatching {
            val json = get(WIKI.format(slug))
            val obj = JSONObject(json)
            obj.optJSONObject("thumbnail")?.optString("source") ?: ""
        }.getOrDefault("")
    }

    private fun get(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "YMGMC-App/1.0")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
