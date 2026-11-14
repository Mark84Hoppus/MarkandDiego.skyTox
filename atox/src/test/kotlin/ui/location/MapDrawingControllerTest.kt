package ltd.evilcorp.atox.ui.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapDrawingControllerTest {
    private fun point(x: Float, y: Float) = DrawingPoint(55.0 + y / 1000, 49.0 + x / 1000, x, y)

    @Test fun normalStrokeFinishesAndResets() {
        val controller = MapDrawingController(3f)
        assertTrue(controller.arm())
        assertTrue(controller.onDown(4, point(0f, 0f)))
        assertTrue(controller.onMove(4, point(5f, 0f)))
        val result = controller.onUp(4, point(10f, 0f))
        assertEquals(3, result.size)
        assertEquals(MapDrawingController.State.IDLE, controller.state)
    }

    @Test fun tapDoesNotCreateRoute() {
        val controller = MapDrawingController(3f)
        controller.arm(); controller.onDown(1, point(1f, 1f))
        assertTrue(controller.onUp(1, point(1f, 1f)).isEmpty())
    }

    @Test fun cancelAndSecondPointerDiscardDraft() {
        val controller = MapDrawingController(3f)
        controller.arm(); controller.onDown(1, point(0f, 0f)); controller.onMove(1, point(8f, 0f))
        controller.cancel()
        assertEquals(MapDrawingController.State.IDLE, controller.state)
        assertTrue(controller.preview().isEmpty())
    }

    @Test fun wrongPointerCannotAppend() {
        val controller = MapDrawingController(3f)
        controller.arm(); controller.onDown(1, point(0f, 0f))
        assertFalse(controller.onMove(2, point(10f, 0f)))
    }
}
