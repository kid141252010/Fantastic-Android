package io.github.kid141252010.fantastic.data

import kotlinx.serialization.json.JsonElement

data class CatalogHit(
    val source: Source,
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val artwork: String? = null,
    val track: Track? = null,
    val explicit: Boolean = false,
    val detailOnly: Boolean = true,
    val attributes: JsonElement? = null,
    val country: String? = if (source == Source.Apple) track?.storefront else "cn",
)
