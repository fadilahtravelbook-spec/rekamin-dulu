package com.bangdillah.rekamindulu.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.HomeActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.permission.PermissionDialogActivity
import com.bangdillah.rekamindulu.utils.AppPreferences

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
    private lateinit var btnAction: Button
    private lateinit var btnSkip: TextView
    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var dot3: View
    private lateinit var appPreferences: AppPreferences

    private var currentSlide = 0
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appPreferences = AppPreferences(this)
        appPreferences.applyTheme()
        setContentView(R.layout.onboarding_screen)
        supportActionBar?.hide()

        viewFlipper = findViewById(R.id.onboarding_view_flipper)
        btnAction = findViewById(R.id.btn_onboarding_action)
        btnSkip = findViewById(R.id.btn_onboarding_skip)
        dot1 = findViewById(R.id.dot_1)
        dot2 = findViewById(R.id.dot_2)
        dot3 = findViewById(R.id.dot_3)

        btnSkip.setOnClickListener {
            finishOnboarding(requestPermissions = false)
        }

        btnAction.setOnClickListener {
            if (currentSlide < 2) {
                goToSlide(currentSlide + 1)
            } else {
                finishOnboarding(requestPermissions = true)
            }
        }

        setupSwipeGesture()
        updateSlideUI()
    }

    private fun setupSwipeGesture() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 80
            private val SWIPE_VELOCITY_THRESHOLD = 80

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX < 0) {
                            // Swipe Left -> Next
                            if (currentSlide < 2) {
                                goToSlide(currentSlide + 1)
                                return true
                            }
                        } else {
                            // Swipe Right -> Prev
                            if (currentSlide > 0) {
                                goToSlide(currentSlide - 1)
                                return true
                            }
                        }
                    }
                }
                return false
            }
        })

        viewFlipper.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun goToSlide(index: Int) {
        if (index != currentSlide && index in 0..2) {
            viewFlipper.setInAnimation(this, android.R.anim.fade_in)
            viewFlipper.setOutAnimation(this, android.R.anim.fade_out)
            viewFlipper.displayedChild = index
            currentSlide = index
            updateSlideUI()
        }
    }


    private fun updateSlideUI() {
        val colorActive = ContextCompat.getColor(this, R.color.colorPrimary)
        val colorInactive = ContextCompat.getColor(this, R.color.colorCardBackground)

        val density = resources.displayMetrics.density
        val wideWidth = (24 * density).toInt()
        val dotWidth = (8 * density).toInt()
        val dotHeight = (8 * density).toInt()

        val dots = arrayOf(dot1, dot2, dot3)
        for (i in dots.indices) {
            val d = dots[i]
            val params = d.layoutParams
            params.height = dotHeight
            if (i == currentSlide) {
                params.width = wideWidth
                d.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorPrimary)
            } else {
                params.width = dotWidth
                d.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorCardBackground)
            }
            d.layoutParams = params
        }

        if (currentSlide == 2) {
            btnAction.text = getString(R.string.onboarding_mulai_sekarang)
            btnSkip.visibility = View.GONE
        } else {
            btnAction.text = getString(R.string.onboarding_lanjut)
            btnSkip.visibility = View.VISIBLE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentSlide > 0) {
            goToSlide(currentSlide - 1)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun finishOnboarding(requestPermissions: Boolean) {
        appPreferences.isOnboardingDone = true
        val homeIntent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (requestPermissions) {
                putExtra("REQUEST_PERMISSIONS_ONBOARDING", true)
            }
        }
        startActivity(homeIntent)
        finish()
    }
}
