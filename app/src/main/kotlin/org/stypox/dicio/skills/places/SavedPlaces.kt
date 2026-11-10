package org.stypox.dicio.skills.places

import androidx.datastore.core.DataStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.settings.GarSavedPlace
import org.stypox.dicio.gar.settings.GarSettings

/** A point on the map, in degrees. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * A place the user saved: an [address] as said or typed, a [point] on the map (where the phone
 * was when the user said "use my current location", or a shared map link), or both. GAR
 * (gar:places): [lookedUp] when the point was found online for the address (NominatimLookup).
 */
data class SavedPlace(val address: String?, val point: GeoPoint?, val lookedUp: Boolean = false) {
    init {
        require(address != null || point != null) { "A saved place needs an address or a point" }
    }
}

/**
 * GAR (gar:places). The user's home and work, kept in GAR's settings ([GarSettings.getHomePlace],
 * [GarSettings.getWorkPlace]) until the user changes or forgets them by voice ("change my home
 * address", "forget my work address") or in the "Home and work" setting
 * ([org.stypox.dicio.gar.settings.GarPlacesSetting]), which writes through this same store.
 */
interface SavedPlaces {
    suspend fun get(kind: SavedPlaceKind): SavedPlace?
    suspend fun set(kind: SavedPlaceKind, place: SavedPlace)

    /** @return whether a place was saved */
    suspend fun forget(kind: SavedPlaceKind): Boolean

    companion object {
        fun of(ctx: SkillContext): SavedPlaces = GarSettingsSavedPlaces(
            EntryPointAccessors.fromApplication(ctx.android.applicationContext, GarEntryPoint::class.java)
                .garSettings()
        )
    }
}

class GarSettingsSavedPlaces(private val settings: DataStore<GarSettings>) : SavedPlaces {
    override suspend fun get(kind: SavedPlaceKind): SavedPlace? = placeIn(settings.data.first(), kind)

    override suspend fun set(kind: SavedPlaceKind, place: SavedPlace) {
        val stored = GarSavedPlace.newBuilder()
            .setAddress(place.address ?: "")
            .setHasCoordinates(place.point != null)
            .setLatitude(place.point?.latitude ?: 0.0)
            .setLongitude(place.point?.longitude ?: 0.0)
            .setPointLookedUp(place.lookedUp && place.point != null)
            .build()
        settings.updateData {
            when (kind) {
                SavedPlaceKind.HOME -> it.toBuilder().setHomePlace(stored).build()
                SavedPlaceKind.WORK -> it.toBuilder().setWorkPlace(stored).build()
            }
        }
    }

    override suspend fun forget(kind: SavedPlaceKind): Boolean {
        val had = get(kind) != null
        settings.updateData {
            when (kind) {
                SavedPlaceKind.HOME -> it.toBuilder().clearHomePlace().build()
                SavedPlaceKind.WORK -> it.toBuilder().clearWorkPlace().build()
            }
        }
        return had
    }

    companion object {
        /** The [kind] saved in [data], as [get] reads it; the "Home and work" setting shows it. */
        fun placeIn(data: GarSettings, kind: SavedPlaceKind): SavedPlace? {
            val stored = when (kind) {
                SavedPlaceKind.HOME -> data.takeIf { it.hasHomePlace() }?.homePlace
                SavedPlaceKind.WORK -> data.takeIf { it.hasWorkPlace() }?.workPlace
            } ?: return null
            val address = stored.address.takeIf { it.isNotBlank() }
            val point = if (stored.hasCoordinates) GeoPoint(stored.latitude, stored.longitude) else null
            return if (address == null && point == null) {
                null
            } else {
                SavedPlace(address, point, lookedUp = point != null && stored.pointLookedUp)
            }
        }
    }
}
