package io.embodit.bonsai.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The garden flow: same paths for every input (D29), select-then-swap (D52), undo, rest, done, shelf (D57), save. */
@OptIn(ExperimentalCoroutinesApi::class)
class GardenTest {
    private class MemStore : GardenStore {
        var pot: SavedPot? = null; var shelf: List<ShelfItem> = emptyList()
        override fun loadPot() = pot?.let { SaveCodec.decode(SaveCodec.encode(it)) }
        override fun savePot(p: SavedPot) { pot = p }
        override fun loadShelf() = shelf
        override fun saveShelf(items: List<ShelfItem>) { shelf = items }
    }

    private fun garden(scope: TestScope, store: MemStore = MemStore(), clock: () -> Long = { scope.testScheduler.currentTime + 1 }): Garden {
        var s = Settings()
        val d = StandardTestDispatcher(scope.testScheduler)
        return Garden(scope, d, clock, store, { s }, { s = it }, {}, {}, Random(1)).also { it.start() }
    }

    @Test fun firstRunGivesAPotWithTheGuide() {
        val sc = TestScope()
        val g = garden(sc)
        assertEquals(Form.SHAKAN, g.state.value.form)
        assertEquals(1, g.state.value.guideStep)
        assertTrue(g.state.value.view.isNotEmpty())
    }

    @Test fun tapSelectsSecondTapCutsAnotherTapSwaps() {
        val sc = TestScope()
        val g = garden(sc)
        val pads = g.tree.pads().filter { !it.isLong }
        g.tapNode(pads[0].id)
        val a = g.state.value.selection
        assertNotNull(a)
        g.tapNode(pads.last().id)
        val b = g.state.value.selection!!
        assertTrue("swapped", b.start != a!!.start)
        val cutsBefore = g.tree.cuts
        g.tapNode(pads.last().id)
        assertNull(g.state.value.selection)
        assertEquals(cutsBefore + 1, g.tree.cuts)
        g.undo()
        assertEquals(cutsBefore, g.tree.cuts)
    }

    @Test fun trunkIsNeverSelected() {
        val sc = TestScope()
        val g = garden(sc)
        g.tapNode(g.tree.nodes.first { it.kind == Kind.TRUNK && it.parent >= 0 }.id)
        assertNull(g.state.value.selection)
        assertEquals(Note.TRUNK, g.state.value.note)
    }

    @Test fun stepInGrowsTheSelection() {
        val sc = TestScope()
        val g = garden(sc)
        val pad = g.tree.pads().first { it.isLong }
        g.tapNode(pad.id)
        val n0 = g.state.value.selection!!.ids.size
        repeat(6) { g.stepIn() }
        val sel = g.state.value.selection!!
        assertTrue(sel.ids.size > n0)
        assertTrue(g.tree.nodes[sel.start].kind != Kind.TRUNK)
        g.stepOut()
        assertTrue(g.state.value.selection!!.ids.size <= sel.ids.size)
    }

    @Test fun waterGrowsAYearAndBlocksUndo() = run {
        val sc = TestScope()
        val g = garden(sc)
        val pad = g.tree.pads().first { it.isLong }
        g.tapNode(pad.id); g.confirmCut("test")
        g.water("test")
        sc.advanceUntilIdle()
        assertEquals(1, g.state.value.year)
        assertTrue(!g.state.value.canUndo)
        assertTrue(g.state.value.undoBlocked)
    }

    @Test fun shelfGetsTheNewPotAtOnce() {
        val sc = TestScope()
        val g = garden(sc)
        g.forceDone()
        g.putOnShelf()
        val st = g.state.value
        assertEquals(1, st.shelf.size)
        assertEquals(Page.SHELF, st.page)
        assertEquals(st.shelf[0].id, st.shelfHighlight)
        g.startPot(Form.KENGAI, 5)
        assertEquals(Form.KENGAI, g.state.value.form)
        assertEquals(Page.TEND, g.state.value.page)
    }

    @Test fun restoreGivesTheSameTree() {
        val sc = TestScope()
        val store = MemStore()
        val g = garden(sc, store)
        g.tapNode(g.tree.pads().first { it.isLong }.id); g.confirmCut("test")
        g.water("test"); sc.advanceUntilIdle()
        val before = SaveCodec.encode(SavedPot(g.tree, PotMeta()))
        val g2 = garden(sc, store)
        assertEquals(before, SaveCodec.encode(SavedPot(g2.tree, PotMeta())))
        assertEquals(g.state.value.score, g2.state.value.score)
    }

    @Suppress("unused") private val keep = Dispatchers.Unconfined
}
