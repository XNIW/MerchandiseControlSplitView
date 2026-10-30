package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotB6AxTargetJoinTest {
    private val viewport = PilotB6AxTargetJoin.Bounds(0, 0, 100, 100)
    private fun box(top: Int, bottom: Int) = PilotB6AxTargetJoin.Bounds(0, top, 100, bottom)
    private fun node(id: Int, parent: Int?, text: String? = null, top: Int = 10, bottom: Int = 20,
        visible: Boolean = true, clickable: Boolean = false, item: Boolean = false, list: Boolean = false) =
        PilotB6AxTargetJoin.Node(id, parent, text, visible, box(top, bottom), clickable, item, list)
    private val root get() = node(0, null, top = 0, bottom = 100)
    private fun target(anchor: String, vararg fields: String) = PilotB6AxTargetJoin.Target(anchor, fields.toList())
    private fun evaluate(nodes: List<PilotB6AxTargetJoin.Node>, before: PilotB6AxTargetJoin.Target,
        after: PilotB6AxTargetJoin.Target = before, absent: List<String> = emptyList()) =
        PilotB6AxTargetJoin.evaluate(nodes, 0, viewport, before, after, absent)

    @Test fun fieldsAndIdentityInTheSameActualCardQualify() {
        val result = evaluate(listOf(root, node(1,0,top=10,bottom=40,clickable=true),
            node(2,1,"barcode"),node(3,1,"new",top=22,bottom=32)),target("barcode","barcode","new"))
        assertTrue(result.afterMatches)
        assertEquals(1,result.after.containerID)
    }
    @Test fun toolbarAnchorCannotBorrowFieldsFromAnotherCard() {
        val result = evaluate(listOf(root,node(1,0,"anchor",top=0,bottom=8,clickable=true),
            node(2,0,top=20,bottom=40,clickable=true),node(3,2,"new",top=22,bottom=30)),target("anchor","new"))
        assertFalse(result.afterMatches)
        assertEquals(1,result.after.containerID)
    }
    @Test fun nonActionableToolbarAnchorCannotUseRootAsTarget() {
        val result=evaluate(listOf(root,node(1,0,"anchor",top=0,bottom=8),
            node(2,0,top=20,bottom=40,clickable=true),node(3,2,"new",top=22,bottom=30)),target("anchor","new"))
        assertFalse(result.context)
        assertFalse(result.afterMatches)
    }
    @Test fun offscreenTargetCannotBorrowMatchingValuesFromVisibleNeighbor() {
        val result=evaluate(listOf(root,node(1,0,top=101,bottom=140,clickable=true),
            node(2,1,"target",top=102,bottom=112,visible=false),node(3,1,"new",top=114,bottom=124,visible=false),
            node(4,0,top=20,bottom=40,clickable=true),node(5,4,"new",top=22,bottom=30)),target("target","new"))
        assertFalse(result.context)
        assertFalse(result.afterMatches)
    }
    @Test fun visibleTargetCannotBorrowMissingFieldFromNeighbor() {
        val result=evaluate(listOf(root,node(1,0,top=10,bottom=40,clickable=true),node(2,1,"target"),
            node(3,0,top=50,bottom=80,clickable=true),node(4,3,"new",top=52,bottom=62)),target("target","new"))
        assertTrue(result.context)
        assertFalse(result.afterMatches)
    }
    @Test fun duplicateAnchorRemainsAmbiguousEvenIfOneIsHidden() {
        val result=evaluate(listOf(root,node(1,0,top=10,bottom=40,clickable=true),node(2,1,"target"),node(3,1,"new",top=22,bottom=32),
            node(4,0,"target",top=101,bottom=110,visible=false)),target("target","new"))
        assertFalse(result.context)
    }
    @Test fun createUsesSeparateControlBaselineAndActualNewRow() {
        val baseline=listOf(root,node(1,0,top=10,bottom=40,clickable=true),node(2,1,"control"))
        val before=target("control","control"); val after=target("created barcode","created barcode","new")
        val first=evaluate(baseline,before,after)
        assertTrue(first.context);assertTrue(first.beforeMatches);assertFalse(first.afterMatches)
        val result=evaluate(baseline+listOf(node(3,0,top=50,bottom=80,clickable=true),
            node(4,3,"created barcode",top=52,bottom=62),node(5,3,"new",top=64,bottom=74)),before,after)
        assertTrue(result.afterMatches);assertEquals(3,result.after.containerID)
    }
    @Test fun deleteBaselineTargetAndAfterControlUseDifferentSubtrees() {
        val before=target("removed barcode","removed barcode","old");val after=target("control barcode","control barcode")
        val control=listOf(root,node(1,0,top=10,bottom=40,clickable=true),node(2,1,"control barcode"))
        val initial=evaluate(control+listOf(node(3,0,top=50,bottom=80,clickable=true),
            node(4,3,"removed barcode",top=52,bottom=62),node(5,3,"old",top=64,bottom=74)),before,after,listOf("removed barcode"))
        assertTrue(initial.beforeMatches);assertFalse(initial.afterMatches)
        val removed=evaluate(control,before,after,listOf("removed barcode"))
        assertFalse(removed.beforeMatches);assertTrue(removed.afterMatches);assertEquals(1,removed.after.containerID)
    }
    @Test fun priceListWithoutActualItemContainerIsNotQualified() {
        val result=evaluate(listOf(root,node(1,0,top=10,bottom=80,list=true),
            node(2,1,"price"),node(3,1,"date",top=22,bottom=32),node(4,1,"source",top=34,bottom=44)),
            target("price","price","date","source"))
        assertFalse(result.context);assertFalse(result.afterMatches)
    }
    @Test fun actualCollectionItemCanQualifyWithoutClickAction() {
        val result=evaluate(listOf(root,node(1,0,top=10,bottom=90,list=true),node(2,1,top=10,bottom=50,item=true),
            node(3,2,"price"),node(4,2,"date",top=22,bottom=32),node(5,2,"source",top=34,bottom=44)),
            target("price","price","date","source"))
        assertTrue(result.afterMatches);assertEquals(2,result.after.containerID)
    }
    @Test fun partiallyClippedTargetDoesNotQualify() {
        val result=evaluate(listOf(root,node(1,0,top=80,bottom=110,clickable=true),node(2,1,"target",top=82,bottom=92)),target("target","target"))
        assertFalse(result.context)
    }
    @Test(expected = IllegalArgumentException::class) fun malformedParentCycleFailsClosed() {
        evaluate(listOf(root,node(1,2,"target"),node(2,1,"new")),target("target","new"))
    }
}
