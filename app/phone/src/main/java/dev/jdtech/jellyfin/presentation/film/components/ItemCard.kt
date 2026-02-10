package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings

@Composable
fun ItemCard(
    item: FindroidItem,
    direction: Direction,
    onClick: (FindroidItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val width =
        when (direction) {
            Direction.HORIZONTAL -> 260
            Direction.VERTICAL -> 150
        }
    Column(
        modifier =
            modifier
                .width(width.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = { onClick(item) })
    ) {
        Surface(shape = MaterialTheme.shapes.small) {
            Box {
                ItemPoster(item = item, direction = direction)
                Row(
                    modifier =
                        Modifier.align(Alignment.TopEnd).padding(MaterialTheme.spacings.small),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                ) {
                    if (item.isDownloaded()) DownloadedBadge()
                    if (item.played) PlayedBadge()
                    if (item.playCount > 0) PlayCountBadge(count = item.playCount)
                    item.unplayedItemCount?.takeIf { it > 0 }?.let { ItemCountBadge(it) }
                }
                if (direction == Direction.HORIZONTAL) {
                    ProgressBar(
                        item = item,
                        width = width,
                        modifier =
                            Modifier.align(Alignment.BottomStart)
                                .padding(MaterialTheme.spacings.small),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
        Text(
            text = if (item is FindroidEpisode) item.seriesName else item.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (item is FindroidEpisode) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item is FindroidEpisode) {
            Text(
                text =
                    stringResource(
                        id = R.string.episode_name_extended,
                        item.parentIndexNumber,
                        item.indexNumber,
                        item.name,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
fun PlayCountBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = null,
                modifier = Modifier.height(10.dp),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewMovie() {
    FindroidTheme { ItemCard(item = dummyMovie, direction = Direction.HORIZONTAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewMovieVertical() {
    FindroidTheme { ItemCard(item = dummyMovie, direction = Direction.VERTICAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewEpisode() {
    FindroidTheme { ItemCard(item = dummyEpisode, direction = Direction.HORIZONTAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewEpisodeVertical() {
    FindroidTheme { ItemCard(item = dummyEpisode, direction = Direction.VERTICAL, onClick = {}) }
}
