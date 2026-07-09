package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.presentation.theme.spacings

@Composable
fun HomeLibraryFolders(
    folders: List<FindroidCollection>,
    itemsPadding: PaddingValues,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "媒体库文件夹",
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemsPadding),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
        LazyRow(
            contentPadding = itemsPadding,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        ) {
            items(folders, key = { it.id }) { folder ->
                ItemCard(
                    item = folder,
                    direction = Direction.HORIZONTAL,
                    onClick = { onAction(HomeAction.OnLibraryClick(folder)) },
                )
            }
        }
    }
}
