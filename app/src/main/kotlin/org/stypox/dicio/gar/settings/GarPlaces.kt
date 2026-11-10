package org.stypox.dicio.gar.settings

import android.app.Application
import android.app.KeyguardManager
import androidx.datastore.core.DataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.dicio.skill.skill.Permission
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarKeepScreenOn
import org.stypox.dicio.skills.places.AndroidCardPlaces
import org.stypox.dicio.skills.places.AndroidDeviceLocation
import org.stypox.dicio.skills.places.CardAddresses
import org.stypox.dicio.skills.places.CardPlaces
import org.stypox.dicio.skills.places.DeviceLocation
import org.stypox.dicio.skills.places.GarSettingsSavedPlaces
import org.stypox.dicio.skills.places.LocationLookup
import org.stypox.dicio.skills.places.LocationResult
import org.stypox.dicio.skills.places.MapLinks
import org.stypox.dicio.skills.places.PlaceLinks
import org.stypox.dicio.skills.places.SavedPlace
import org.stypox.dicio.skills.places.SavedPlaceKind
import org.stypox.dicio.skills.places.SavedPlaces
import org.stypox.dicio.skills.places.SharedPlace
import javax.inject.Inject

/**
 * GAR (gar:places). The "Home and work" setting's changes, written to the same [SavedPlaces] the
 * voice flow uses ([org.stypox.dicio.skills.places.PlacesFlow]), so voice and settings agree.
 */
class PlaceEditor(private val saved: SavedPlaces) {
    /**
     * Saves a typed or pasted address or place name; null (nothing saved) when it is blank. A
     * pasted map link or "lat, lon" ([PlaceLinks.parse]) is saved as its point, with its name.
     */
    suspend fun saveTyped(kind: SavedPlaceKind, text: String): SavedPlace? {
        PlaceLinks.parse(text)?.let { return saveShared(kind, it) }
        val address = GarPlaces.typed(text) ?: return null
        return SavedPlace(address, null).also { saved.set(kind, it) }
    }

    /** Saves a place shared from a maps app ([org.stypox.dicio.gar.settings.GarPlaceShareActivity]). */
    suspend fun saveShared(kind: SavedPlaceKind, shared: SharedPlace): SavedPlace =
        SavedPlace(shared.name?.let { GarPlaces.typed(it) }, shared.point).also { saved.set(kind, it) }

    /**
     * "Use where I am now": finds the phone as "here" does by voice ([LocationLookup.locate],
     * which asks for access first), and saves the point when found and precise. An approximate
     * fix ([org.stypox.dicio.skills.places.DeviceFix.precise] false) can be 2 km off, so it is
     * returned unsaved, as by voice.
     */
    suspend fun saveHere(kind: SavedPlaceKind, location: DeviceLocation): LocationResult {
        val result = LocationLookup.locate(location, withName = false)
        if (result is LocationResult.Found && result.fix.precise) {
            saved.set(kind, SavedPlace(null, result.fix.point))
        }
        return result
    }

    /** Saves an address from the "You" contact's card. */
    suspend fun saveCard(kind: SavedPlaceKind, address: String) {
        saved.set(kind, SavedPlace(address, null))
    }

    suspend fun clear(kind: SavedPlaceKind) {
        saved.forget(kind)
    }
}

object GarPlaces {
    /** A typed or pasted address on one line, or null when blank. */
    fun typed(text: String): String? = CardAddresses.oneLine(text)?.replace(SPACES, " ")

    /**
     * What a row shows of [place]: the address, "Saved spot (40.71278, -74.00597)" for a
     * point, "Not set", or nothing over the lock screen ([locked] true, or null before it was read).
     */
    fun summary(place: SavedPlace?, locked: Boolean?, string: (Int, Array<out Any>) -> String): String {
        fun s(id: Int, vararg args: Any) = string(id, args)
        return when {
            locked != false -> s(R.string.gar_places_locked)
            place == null -> s(R.string.gar_places_not_set)
            place.address != null -> place.address
            else -> s(
                R.string.gar_places_point,
                MapLinks.format(place.point!!.latitude),
                MapLinks.format(place.point.longitude),
            )
        }
    }

    private val SPACES = Regex("\\s+")
}

/**
 * The "Home and work" setting's state: the saved places as GAR's settings hold them, whether the
 * phone is locked (as [GarSettingsViewModel.locked]), the card's addresses and the editor's writes.
 */
@HiltViewModel
class GarPlacesViewModel @Inject constructor(
    private val application: Application,
    private val dataStore: DataStore<GarSettings>,
) : AndroidViewModel(application) {
    private val editor = PlaceEditor(GarSettingsSavedPlaces(dataStore))
    private val card: CardPlaces = AndroidCardPlaces(application, dataStore)
    private val checks = MutableStateFlow(0)

    val places: StateFlow<Map<SavedPlaceKind, SavedPlace?>?> = dataStore.data
        .map { data -> SavedPlaceKind.entries.associateWith { GarSettingsSavedPlaces.placeIn(data, it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Whether the phone is locked, read again at every unlock and at [check]; null until read. */
    val locked: StateFlow<Boolean?> = combine(GarKeepScreenOn.unlocks(application), checks) { _, _ ->
        application.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun check() {
        checks.value += 1
    }

    /** The [kind] addresses on the "You" card; none without contacts access or while locked. */
    suspend fun cardAddresses(kind: SavedPlaceKind): List<String> =
        if (locked.value == false) card.addresses(kind) else listOf()

    suspend fun saveTyped(kind: SavedPlaceKind, text: String) = editor.saveTyped(kind, text)

    /** @param requester asks for location access with Android's dialog, as Dicio's requester does */
    suspend fun saveHere(kind: SavedPlaceKind, requester: suspend (List<Permission>) -> Boolean) =
        editor.saveHere(kind, AndroidDeviceLocation(application, requester))

    suspend fun saveCard(kind: SavedPlaceKind, address: String) = editor.saveCard(kind, address)

    suspend fun clear(kind: SavedPlaceKind) = editor.clear(kind)
}
