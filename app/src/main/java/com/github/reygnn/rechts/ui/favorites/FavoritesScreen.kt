package com.github.reygnn.rechts.ui.favorites

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.rechts.R
import com.github.reygnn.rechts.domain.Favorite
import com.github.reygnn.rechts.ui.theme.RechtsTheme

/**
 * Full-screen favorites overlay. Rendered as a sibling ON TOP of the browser
 * (see BrowserScreen) so the live WebView stays composed underneath — this is
 * not a screen swap, there's no navigation library, just a boolean.
 *
 * The reorderable list is a [RecyclerView] + [ItemTouchHelper] hosted in an
 * [AndroidView] — the same proven drag mechanism Kolibri_Launcher uses. rechts
 * already drops to View interop for the WebView/SwipeRefreshLayout, so this
 * fits. Rows are rendered with Compose ([FavoriteRow]) inside per-item
 * [ComposeView]s, so there's no XML item layout and they keep the M3 theme.
 *
 * @param favorites  the list to show, newest last.
 * @param onOpenUrl  open a favorite's URL (the caller loads it and closes this).
 * @param onRemove   delete the favorite with the given URL.
 * @param onReorder  persist a new order after a drag-and-drop drop.
 * @param onClose    leave the overlay (back arrow / system back).
 */
@Composable
fun FavoritesScreen(
    favorites: List<Favorite>,
    onOpenUrl: (String) -> Unit,
    onRemove: (String) -> Unit,
    onReorder: (List<Favorite>) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.favorites_back),
                    )
                }
                Text(
                    text = stringResource(R.string.favorites_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            HorizontalDivider()

            if (favorites.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.favorites_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                FavoritesList(
                    favorites = favorites,
                    onOpenUrl = onOpenUrl,
                    onRemove = onRemove,
                    onReorder = onReorder,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun FavoritesList(
    favorites: List<Favorite>,
    onOpenUrl: (String) -> Unit,
    onRemove: (String) -> Unit,
    onReorder: (List<Favorite>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The RecyclerView is built once; keep the freshest callbacks behind State so
    // recomposition doesn't force a rebuild and lose scroll/drag position.
    val currentOpen = rememberUpdatedState(onOpenUrl)
    val currentRemove = rememberUpdatedState(onRemove)
    val currentReorder = rememberUpdatedState(onReorder)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val recycler = RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            // touchHelper is referenced by the adapter's drag-start callback, set
            // just below — lateinit breaks the construction cycle.
            lateinit var touchHelper: ItemTouchHelper
            val adapter = FavoritesAdapter(
                onOpen = { currentOpen.value(it) },
                onRemove = { currentRemove.value(it) },
                onStartDrag = { holder -> touchHelper.startDrag(holder) },
            )
            recycler.adapter = adapter

            touchHelper = ItemTouchHelper(
                FavoritesDragCallback(
                    adapter = adapter,
                    onReordered = { currentReorder.value(it) },
                ),
            ).also { it.attachToRecyclerView(recycler) }

            recycler
        },
        update = { recycler ->
            (recycler.adapter as FavoritesAdapter).submit(favorites)
        },
    )
}

/**
 * Vertical-only drag reorder. Drag is initiated from the row's handle (long
 * press is disabled) so a plain tap still opens the favorite. The live reorder
 * happens in the adapter; the final order is persisted once, on drop.
 */
private class FavoritesDragCallback(
    private val adapter: FavoritesAdapter,
    private val onReordered: (List<Favorite>) -> Unit,
) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

    override fun isLongPressDragEnabled(): Boolean = false

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        adapter.moveItem(from, to)
        return true
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        // Fade the dragged row, matching Kolibri_Launcher's drag feedback.
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) viewHolder?.itemView?.alpha = 0.7f
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.alpha = 1f
        onReordered(adapter.currentOrder())
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
}

/**
 * RecyclerView adapter whose item views are [ComposeView]s, so the rows are
 * ordinary Compose/M3 ([FavoriteRow]) while the drag mechanics stay with
 * ItemTouchHelper.
 */
private class FavoritesAdapter(
    private val onOpen: (String) -> Unit,
    private val onRemove: (String) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<FavoritesAdapter.Holder>() {

    private val items = mutableListOf<Favorite>()

    /**
     * Replace the list from the DataStore flow. A drop persists the new order,
     * which the flow then echoes back identically — short-circuit that so the
     * echo doesn't `notifyDataSetChanged()` and interrupt the drag animation.
     */
    fun submit(new: List<Favorite>) {
        if (items == new) return
        items.clear()
        items.addAll(new)
        notifyDataSetChanged()
    }

    /** Live reorder during a drag; persisted on drop via [currentOrder]. */
    fun moveItem(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun currentOrder(): List<Favorite> = items.toList()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val composeView = ComposeView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // The right strategy for a ComposeView in a RecyclerView: dispose the
            // composition when the view leaves the window or is recycled.
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
        }
        return Holder(composeView)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val favorite = items[position]
        holder.composeView.setContent {
            // A ComposeView starts its own composition that doesn't inherit the
            // outer theme — re-apply it so rows match the app's M3 colors.
            RechtsTheme {
                FavoriteRow(
                    favorite = favorite,
                    onOpen = { onOpen(favorite.url) },
                    onRemove = { onRemove(favorite.url) },
                    onDragHandlePressed = { onStartDrag(holder) },
                )
            }
        }
    }

    class Holder(val composeView: ComposeView) : RecyclerView.ViewHolder(composeView)
}

@Composable
private fun FavoriteRow(
    favorite: Favorite,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onDragHandlePressed: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_drag_handle),
            contentDescription = stringResource(R.string.favorite_reorder),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(8.dp)
                // Touching the handle starts the drag (long-press drag is off),
                // so a tap elsewhere on the row still opens the favorite.
                // Consume the down so a tap ON the handle doesn't also reach the
                // row's clickable and open the URL.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        onDragHandlePressed()
                    }
                },
        )
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                text = favorite.title.ifBlank { favorite.url },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = favorite.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.favorite_remove))
        }
    }
}
