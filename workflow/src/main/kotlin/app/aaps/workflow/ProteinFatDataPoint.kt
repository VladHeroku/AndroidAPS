package app.aaps.workflow

import android.content.Context
import android.graphics.Paint
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.Shape
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.objects.meal.ProteinFatEntry
import java.util.Locale

class ProteinFatDataPoint(
    val data: ProteinFatEntry,
    private val rh: ResourceHelper
) : DataPointWithLabelInterface {

    private var yValue = 0.0

    override fun getX(): Double = data.timestamp.toDouble()
    override fun getY(): Double = yValue
    override fun setY(y: Double) { yValue = y }

    override val label: String
        get() = String.format(
            Locale.getDefault(),
            "P %.0fg · F %.0fg · eq %.1fg · %.2fU",
            data.proteinG,
            data.fatG,
            data.delayedCarbEquivalentG,
            data.advisoryInsulinU
        )

    override val duration: Long = 0L
    override val shape: Shape = Shape.GENERAL
    override val size: Float = 2f
    override val paintStyle: Paint.Style = Paint.Style.FILL

    override fun color(context: Context?): Int =
        rh.gac(context, app.aaps.core.ui.R.attr.cobColor)
}
