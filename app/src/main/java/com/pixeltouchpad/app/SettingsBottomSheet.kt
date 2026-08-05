package com.pixeltouchpad.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsBottomSheet : BottomSheetDialogFragment() {

    var onSensitivityChanged: ((Float) -> Unit)? = null
    var onScrollSensitivityChanged: ((Float) -> Unit)? = null
    var onDragLockToggled: ((Boolean) -> Unit)? = null
    var onEndDragOnSingleTapToggled: ((Boolean) -> Unit)? = null
    var onDiagnoseClicked: (() -> Unit)? = null
    var onDisconnectClicked: (() -> Unit)? = null

    private var currentSensitivity = 1.5f
    private var currentScrollSensitivity = 0.08f
    private var currentDragLockEnabled = true
    private var currentEndDragOnSingleTap = false

    companion object {
        fun newInstance(
            cursorSens: Float,
            scrollSens: Float,
            dragLockEnabled: Boolean,
            endDragOnSingleTap: Boolean
        ): SettingsBottomSheet {
            return SettingsBottomSheet().apply {
                arguments = Bundle().apply {
                    putFloat("cursor_sens", cursorSens)
                    putFloat("scroll_sens", scrollSens)
                    putBoolean("drag_lock", dragLockEnabled)
                    putBoolean("end_drag_single_tap", endDragOnSingleTap)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSensitivity = arguments?.getFloat("cursor_sens", 1.5f) ?: 1.5f
        currentScrollSensitivity = arguments?.getFloat("scroll_sens", 0.08f) ?: 0.08f
        currentDragLockEnabled = arguments?.getBoolean("drag_lock", true) ?: true
        currentEndDragOnSingleTap = arguments?.getBoolean("end_drag_single_tap", false) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_settings, container, false)

        val cursorLabel = view.findViewById<TextView>(R.id.labelCursorSens)
        val cursorSeek = view.findViewById<SeekBar>(R.id.seekCursorSens)
        val scrollLabel = view.findViewById<TextView>(R.id.labelScrollSens)
        val scrollSeek = view.findViewById<SeekBar>(R.id.seekScrollSens)
        val switchDragLock = view.findViewById<SwitchMaterial>(R.id.switchDragLock)
        val switchEndDragOnSingleTap = view.findViewById<SwitchMaterial>(R.id.switchEndDragOnSingleTap)
        val btnDiagnose = view.findViewById<Button>(R.id.btnDiagnose)
        val btnDisconnect = view.findViewById<Button>(R.id.btnDisconnect)

        // Cursor sensitivity: 0.5 to 4.0, step 0.1 → max = 35
        cursorSeek.max = 35
        cursorSeek.progress = ((currentSensitivity - 0.5f) * 10).toInt().coerceIn(0, 35)
        cursorLabel.text = "Cursor: %.1f".format(currentSensitivity)

        cursorSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 0.5f + progress * 0.1f
                cursorLabel.text = "Cursor: %.1f".format(value)
                if (fromUser) onSensitivityChanged?.invoke(value)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Scroll sensitivity: 0.01 to 0.20, step 0.01 → max = 19
        scrollSeek.max = 19
        scrollSeek.progress = ((currentScrollSensitivity - 0.01f) * 100).toInt().coerceIn(0, 19)
        scrollLabel.text = "Scroll: %.2f".format(currentScrollSensitivity)

        scrollSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 0.01f + progress * 0.01f
                scrollLabel.text = "Scroll: %.2f".format(value)
                if (fromUser) onScrollSensitivityChanged?.invoke(value)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        switchDragLock.isChecked = currentDragLockEnabled
        switchDragLock.setOnCheckedChangeListener { _, isChecked ->
            onDragLockToggled?.invoke(isChecked)
        }

        switchEndDragOnSingleTap.isChecked = currentEndDragOnSingleTap
        switchEndDragOnSingleTap.setOnCheckedChangeListener { _, isChecked ->
            onEndDragOnSingleTapToggled?.invoke(isChecked)
        }

        btnDiagnose.setOnClickListener { onDiagnoseClicked?.invoke() }
        btnDisconnect.setOnClickListener {
            onDisconnectClicked?.invoke()
            dismiss()
        }

        return view
    }
}
