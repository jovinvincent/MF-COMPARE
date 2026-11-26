package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DateFormatUtils
import com.example.data.FavouriteFundUiItem
import com.example.ui.theme.GainGreenDark
import com.example.ui.theme.GainGreenLight
import com.example.ui.theme.IndexAmberDark
import com.example.ui.theme.IndexAmberLight
import com.example.ui.theme.LossRedDark
import com.example.ui.theme.LossRedLight
import com.example.ui.theme.NumericTextStyle
import java.util.Locale

@Composable
fun FavouritesScreen(
    favourites: List<FavouriteFundUiItem>,
    isDark: Boolean,
    onCompareSelected: (List<String>) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onGoToSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    var checkedCodes by remember(favourites) {
        mutableStateOf(
            emptySet<String>()
        )
    }

    val gainColor = if (isDark) GainGreenDark else GainGreenLight
    val lossColor = if (isDark) LossRedDark else LossRedLight
    val indexBadgeColor = if (isDark) IndexAmberDark else IndexAmberLight

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (favourites.isEmpty()) {
                item(key = "favourites_empty") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BookmarkBorder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "No Saved Favourites Yet",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Bookmark Direct Growth mutual funds or indices from Compare search or Rankings for quick multi-fund comparison.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onGoToSearch) {
                                Text("Find Funds to Bookmark")
                            }
                        }
                    }
                }
            } else {
                // Action Bar for Multi-Select Comparison
                item(key = "favourites_action_bar") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${favourites.size} Saved Favourite(s)",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Select funds or tap any card to compare",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (checkedCodes.isEmpty()) {
                                    OutlinedButton(
                                        onClick = { checkedCodes = favourites.map { it.schemeCode }.toSet() },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("Select All", style = MaterialTheme.typography.labelMedium)
                                    }
                                } else {
                                    Button(
                                        onClick = { onCompareSelected(checkedCodes.toList()) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.testTag("compare_selected_favourites_button")
                                    ) {
                                        Icon(Icons.Filled.ShowChart, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Compare (${checkedCodes.size})", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }

                items(favourites, key = { it.schemeCode }) { item ->
                    val isChecked = checkedCodes.contains(item.schemeCode)
                    FavouriteCardRow(
                        item = item,
                        isChecked = isChecked,
                        gainColor = gainColor,
                        lossColor = lossColor,
                        indexBadgeColor = indexBadgeColor,
                        onCheckedChange = { checked ->
                            checkedCodes = if (checked) {
                                checkedCodes + item.schemeCode
                            } else {
                                checkedCodes - item.schemeCode
                            }
                        },
                        onCardClick = { onCompareSelected(listOf(item.schemeCode)) },
                        onToggleFav = { onToggleFavourite(item.schemeCode) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FavouriteCardRow(
    item: FavouriteFundUiItem,
    isChecked: Boolean,
    gainColor: Color,
    lossColor: Color,
    indexBadgeColor: Color,
    onCheckedChange: (Boolean) -> Unit,
    onCardClick: () -> Unit,
    onToggleFav: () -> Unit
) {
    Card(
        onClick = onCardClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("favourite_card_${item.schemeCode}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = onCheckedChange
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = if (item.isIndex) indexBadgeColor.copy(alpha = 0.18f) else gainColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (item.isIndex) "INDEX" else "DIRECT GROWTH",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (item.isIndex) indexBadgeColor else gainColor,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                        Text(
                            text = item.amcName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = item.schemeName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onToggleFav,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = "Remove favourite",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn(
                    label = "Latest ${if (item.isIndex) "Level" else "NAV"}",
                    value = item.latestNav?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                    subValue = DateFormatUtils.formatShort(item.latestNavEpochDay),
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
                MetricColumn(
                    label = "1M Return",
                    value = item.return1M?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "N/A",
                    subValue = "Trailing 30D",
                    valueColor = when {
                        item.return1M == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        item.return1M >= 0.0 -> gainColor
                        else -> lossColor
                    }
                )
                MetricColumn(
                    label = "1Y Return",
                    value = item.return1Y?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "N/A",
                    subValue = "Trailing 365D",
                    valueColor = when {
                        item.return1Y == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        item.return1Y >= 0.0 -> gainColor
                        else -> lossColor
                    }
                )
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    subValue: String,
    valueColor: Color
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = NumericTextStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = valueColor
        )
        Text(
            text = subValue,
            style = NumericTextStyle.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

