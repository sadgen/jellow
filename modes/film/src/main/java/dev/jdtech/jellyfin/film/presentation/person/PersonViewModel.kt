package dev.jdtech.jellyfin.film.presentation.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

@HiltViewModel
class PersonViewModel @Inject internal constructor(private val repo: JellyfinRepository) :
    ViewModel() {
    val repository: JellyfinRepository get() = repo
    private val _state = MutableStateFlow(PersonState())
    val state = _state.asStateFlow()

    private var rawMovies: List<FindroidMovie> = emptyList()
    private var rawShows: List<FindroidShow> = emptyList()

    fun loadPerson(personId: UUID) {
        viewModelScope.launch {
            try {
                val person = repository.getPerson(personId)

                val items =
                    repository.getPersonItems(
                        personIds = listOf(personId),
                        includeTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                        recursive = true,
                    )

                rawMovies = items.filterIsInstance<FindroidMovie>()
                rawShows = items.filterIsInstance<FindroidShow>()

                _state.emit(
                    _state.value.copy(
                        person = person,
                        starredInMovies = applySortItems(rawMovies, _state.value.sortBy, _state.value.sortOrder),
                        starredInShows = applySortItems(rawShows, _state.value.sortBy, _state.value.sortOrder),
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    fun onAction(action: PersonAction) {
        when (action) {
            is PersonAction.ToggleViewMode -> {
                viewModelScope.launch {
                    _state.emit(_state.value.copy(isListView = !_state.value.isListView))
                }
            }
            is PersonAction.UpdateSort -> {
                viewModelScope.launch {
                    _state.emit(
                        _state.value.copy(
                            sortBy = action.sortBy,
                            sortOrder = action.sortOrder,
                            starredInMovies = applySortItems(rawMovies, action.sortBy, action.sortOrder),
                            starredInShows = applySortItems(rawShows, action.sortBy, action.sortOrder),
                        )
                    )
                }
            }
            else -> Unit
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : FindroidItem> applySortItems(
        items: List<T>,
        sortBy: SortBy,
        sortOrder: SortOrder,
    ): List<T> {
        val comparator: Comparator<FindroidItem> = when (sortBy) {
            SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.PLAYTIME -> compareBy<FindroidItem> { it.runtimeTicks }
            SortBy.PLAY_COUNT -> compareBy<FindroidItem> { it.playCount }
            SortBy.IMDB_RATING -> compareBy<FindroidItem> { 
                ratingValue(it)
            }
            SortBy.RELEASE_DATE -> compareBy<FindroidItem> {
                val date = (it as? FindroidMovie)?.premiereDate
                    ?: (it as? FindroidShow)?.productionYear?.let { year ->
                        LocalDateTime.of(year, 1, 1, 0, 0)
                    }
                date?.toEpochSecond(ZoneOffset.UTC) ?: Long.MAX_VALUE
            }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }

        return if (sortOrder == SortOrder.ASCENDING) {
            items.sortedWith(comparator as Comparator<T>)
        } else {
            items.sortedWith(comparator.reversed() as Comparator<T>)
        }
    }

    private fun ratingValue(item: FindroidItem): Double {
        return -((item as? FindroidMovie)?.communityRating?.toDouble()
            ?: (item as? FindroidShow)?.communityRating?.toDouble()
            ?: 0.0)
    }
}
