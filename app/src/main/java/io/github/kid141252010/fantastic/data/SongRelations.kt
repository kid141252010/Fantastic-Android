package io.github.kid141252010.fantastic.data

import io.github.kid141252010.fantastic.FantasticApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SongRelations(val artist: CatalogHit? = null, val album: CatalogHit? = null)

private fun Track.validRelation(kind: String, relationId: String): Boolean =
    relationId.isNotBlank() && relationId != "0" && validCatalogId(source, kind, relationId)

internal fun Track.songRelations(fallbackCountry: String? = null): SongRelations {
    fun relation(kind: String, relationId: String, name: String, evidence: String?, artwork: String?, subtitle: String = ""): CatalogHit? {
        if (!validRelation(kind, relationId)) return null
        return CatalogHit(source, kind, relationId, name.ifBlank { relationId }, subtitle, artwork,
            detailOnly = true, country = if (source == Source.Apple)
                evidence.normalizedAppleCountry() ?: country.normalizedAppleCountry() ?: fallbackCountry.normalizedAppleCountry() else null)
    }
    return SongRelations(
        artist = relation("artist", artistId, artist, artistCountry, artistArtwork(240)),
        album = relation("album", albumId, album, albumCountry, artwork(240), albumArtist),
    )
}

internal fun Track.fillRelationCountriesFrom(reference: Track): Track {
    if (source != Source.Apple || source != reference.source || id != reference.id ||
        !compatibleLibraryCountries(country, reference.country)) return this
    val nextArtist = if (artistCountry == null && artistId == reference.artistId && validRelation("artist", artistId))
        reference.artistCountry.normalizedAppleCountry() else artistCountry
    val nextAlbum = if (albumCountry == null && albumId == reference.albumId && validRelation("album", albumId))
        reference.albumCountry.normalizedAppleCountry() else albumCountry
    return if (nextArtist == artistCountry && nextAlbum == albumCountry) this
        else copy(artistCountry = nextArtist, albumCountry = nextAlbum)
}

internal suspend fun FantasticApp.resolveSongRelations(seed: Track, requestStorefront: String?): SongRelations = withContext(Dispatchers.IO) {
    require(seed.id.isNotBlank() && seed.id != "0" && validCatalogId(seed.source, "song", seed.id)) { "缺少有效歌曲 ID" }
    val captured = if (seed.source == Source.Apple)
        seed.country.normalizedAppleCountry() ?: requestStorefront.normalizedAppleCountry()
            ?: throw IllegalArgumentException("缺少有效 Apple Music 国家") else null
    val details = when (seed.source) {
        Source.Apple -> {
            val resource = apple.entity(AppleCatalogApi.Entity.Song, seed.id, storefront = captured!!)
            check(resource.id == seed.id && resource.type == "songs") { "服务器返回了其他歌曲。" }
            check(resource.catalogCountry(null)?.let { it == captured } != false) { "服务器返回了其他国家的歌曲。" }
            resource.toTrack(captured) ?: error("服务器返回了其他歌曲。")
        }
        Source.QQ -> qq.songTrack(seed.id)
        Source.Netease -> netease.song(seed.id).song.toTrack()
    }
    check(details.source == seed.source && details.id == seed.id) { "服务器返回了其他歌曲。" }
    val known = seed.fillRelationCountriesFrom(details).songRelations(captured)
    val loaded = details.songRelations(captured)
    fun choose(original: CatalogHit?, fresh: CatalogHit?): CatalogHit? {
        if (original == null) return fresh
        if (fresh == null || original.source != fresh.source || original.kind != fresh.kind || original.id != fresh.id ||
            !compatibleLibraryCountries(original.country, fresh.country)) return original
        val title = if (original.title == original.id) fresh.title else original.title
        val artwork = original.artwork ?: fresh.artwork
        return if (title == original.title && artwork == original.artwork) original else original.copy(title = title, artwork = artwork)
    }
    SongRelations(choose(known.artist, loaded.artist), choose(known.album, loaded.album))
}
