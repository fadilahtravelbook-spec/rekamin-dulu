package com.bangdillah.rekamindulu.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView

data class SelectionOption<T>(
    val value: T,
    val title: String,
    val subtitle: String,
    val badgeLabel: String? = null,
    val isRecommended: Boolean = false
)

object SelectionBottomSheetDialog {

    fun <T> show(
        context: Context,
        @DrawableRes iconRes: Int,
        title: String,
        subtitle: String,
        options: List<SelectionOption<T>>,
        selectedValue: T,
        onSelected: (T) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val sheetView = LayoutInflater.from(context).inflate(R.layout.layout_selection_bottom_sheet, null)
        dialog.setContentView(sheetView)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Bind Header
        val imgIcon = sheetView.findViewById<ImageView>(R.id.img_selection_icon)
        val textTitle = sheetView.findViewById<TextView>(R.id.text_selection_title)
        val textSubtitle = sheetView.findViewById<TextView>(R.id.text_selection_subtitle)
        val containerOptions = sheetView.findViewById<LinearLayout>(R.id.container_selection_options)
        val btnCancel = sheetView.findViewById<View>(R.id.btn_selection_cancel)

        imgIcon.setImageResource(iconRes)
        textTitle.text = title
        textSubtitle.text = subtitle

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        containerOptions.removeAllViews()

        val primaryColor = ContextCompat.getColor(context, R.color.colorPrimary)
        val primaryContainerColor = ContextCompat.getColor(context, R.color.colorPrimaryContainer)
        val cardBgColor = ContextCompat.getColor(context, R.color.colorCardBackground)
        val borderColor = ContextCompat.getColor(context, R.color.colorBorder)

        for (option in options) {
            val itemView = LayoutInflater.from(context).inflate(R.layout.item_selection_option, containerOptions, false)
            val card = itemView.findViewById<MaterialCardView>(R.id.card_selection_option)
            val badgeContainer = itemView.findViewById<FrameLayout>(R.id.container_option_badge)
            val textBadge = itemView.findViewById<TextView>(R.id.text_option_badge)
            val textOptionTitle = itemView.findViewById<TextView>(R.id.text_option_title)
            val textOptionSubtitle = itemView.findViewById<TextView>(R.id.text_option_subtitle)
            val textTag = itemView.findViewById<TextView>(R.id.text_option_tag)
            val imgCheck = itemView.findViewById<ImageView>(R.id.img_option_check)

            textOptionTitle.text = option.title
            textOptionSubtitle.text = option.subtitle

            if (!option.badgeLabel.isNullOrEmpty()) {
                textBadge.text = option.badgeLabel
                badgeContainer.visibility = View.VISIBLE
            } else {
                badgeContainer.visibility = View.GONE
            }

            if (option.isRecommended) {
                textTag.visibility = View.VISIBLE
                textTag.text = "REKOMENDASI"
            } else {
                textTag.visibility = View.GONE
            }

            val isSelected = (option.value == selectedValue)
            if (isSelected) {
                card.setCardBackgroundColor(primaryContainerColor)
                card.strokeColor = primaryColor
                card.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
                imgCheck.visibility = View.VISIBLE
                imgCheck.setColorFilter(primaryColor)
            } else {
                card.setCardBackgroundColor(cardBgColor)
                card.strokeColor = borderColor
                card.strokeWidth = (1.2 * context.resources.displayMetrics.density).toInt()
                imgCheck.visibility = View.INVISIBLE
            }

            card.setOnClickListener {
                onSelected(option.value)
                dialog.dismiss()
            }

            containerOptions.addView(itemView)
        }

        dialog.show()
    }
}
