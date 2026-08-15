package com.drindexer.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText

/**
 * Bottom sheet for advanced filter and sort options.
 * V2.3: Rotation-safe using Bundle arguments and FragmentResult API.
 */
class FilterBottomSheet : BottomSheetDialogFragment() {

    private var currentFilter = SearchFilter()
    private var scanIds: IntArray = intArrayOf()
    private var scanNames: Array<String> = emptyArray()

    companion object {
        const val TAG = "FilterBottomSheet"
        const val RESULT_KEY = "filter_result"
        const val BUNDLE_FILTER = "filter_bundle"

        private const val ARG_FILTER = "arg_filter"
        private const val ARG_SCAN_IDS = "arg_scan_ids"
        private const val ARG_SCAN_NAMES = "arg_scan_names"

        fun newInstance(
            filter: SearchFilter,
            scans: List<Pair<Int, String>>
        ): FilterBottomSheet {
            return FilterBottomSheet().apply {
                arguments = Bundle().apply {
                    putBundle(ARG_FILTER, filter.toBundle())
                    putIntArray(ARG_SCAN_IDS, scans.map { it.first }.toIntArray())
                    putStringArray(ARG_SCAN_NAMES, scans.map { it.second }.toTypedArray())
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = savedInstanceState ?: arguments
        source?.let {
            it.getBundle(ARG_FILTER)?.let { fb -> currentFilter = SearchFilter.fromBundle(fb) }
            scanIds = it.getIntArray(ARG_SCAN_IDS) ?: intArrayOf()
            scanNames = it.getStringArray(ARG_SCAN_NAMES) ?: emptyArray()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle(ARG_FILTER, currentFilter.toBundle())
        outState.putIntArray(ARG_SCAN_IDS, scanIds)
        outState.putStringArray(ARG_SCAN_NAMES, scanNames)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_filter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCategoryChips(view)
        setupScopeChips(view)
        setupSizeFilter(view)
        setupExtensionFilter(view)
        setupScanFilter(view)
        setupSortOptions(view)
        setupPresets(view)
        setupButtons(view)
    }

    private fun setupCategoryChips(view: View) {
        val chipGroup = view.findViewById<ChipGroup>(R.id.categoryChipGroup)
        chipGroup.removeAllViews()

        for (category in FileCategory.getAllCategoryNames()) {
            val chip = Chip(requireContext()).apply {
                text = "${FileCategory.ICONS[category] ?: ""} ${FileCategory.DISPLAY_NAMES[category] ?: category}"
                isCheckable = true
                isChecked = category == currentFilter.category
                setOnClickListener {
                    currentFilter.category = category
                    if (category != "all") {
                        currentFilter.includeFiles = true
                        currentFilter.includeFolders = false
                        setupScopeChips(view)
                    }
                    updateChipGroup(chipGroup, category)
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun updateChipGroup(chipGroup: ChipGroup, selectedCategory: String) {
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip
            val category = FileCategory.getAllCategoryNames().getOrNull(i)
            chip?.isChecked = category == selectedCategory
        }
    }

    private fun setupScopeChips(view: View) {
        val chipFiles = view.findViewById<Chip>(R.id.chipScopeFiles)
        val chipFolders = view.findViewById<Chip>(R.id.chipScopeFolders)
        val chipAll = view.findViewById<Chip>(R.id.chipScopeAll)

        when {
            currentFilter.includeFiles && currentFilter.includeFolders -> chipAll.isChecked = true
            currentFilter.includeFolders -> chipFolders.isChecked = true
            else -> chipFiles.isChecked = true
        }

        chipFiles.setOnClickListener {
            currentFilter.includeFiles = true
            currentFilter.includeFolders = false
        }
        chipFolders.setOnClickListener {
            currentFilter.includeFiles = false
            currentFilter.includeFolders = true
        }
        chipAll.setOnClickListener {
            currentFilter.includeFiles = true
            currentFilter.includeFolders = true
        }
    }

    private fun setupSizeFilter(view: View) {
        val minInput = view.findViewById<TextInputEditText>(R.id.minSizeInput)
        val maxInput = view.findViewById<TextInputEditText>(R.id.maxSizeInput)
        if (currentFilter.minSizeMB > 0) minInput.setText(currentFilter.minSizeMB.toInt().toString())
        if (currentFilter.maxSizeMB < Float.MAX_VALUE) maxInput.setText(currentFilter.maxSizeMB.toInt().toString())
    }

    private fun setupExtensionFilter(view: View) {
        val extInput = view.findViewById<TextInputEditText>(R.id.extensionInput)
        extInput.setText(currentFilter.extensionFilter)
    }

    private fun setupScanFilter(view: View) {
        // v4: scan scope is controlled only by the Search screen's derived
        // multi-select scope. Keeping a second independent scan selector here
        // would recreate the old ambiguous scope behavior.
        view.findViewById<View>(R.id.scanFilterLabel).visibility = View.GONE
        view.findViewById<View>(R.id.scanSpinner).visibility = View.GONE
    }

    private fun setupSortOptions(view: View) {
        val fieldSpinner = view.findViewById<Spinner>(R.id.sortFieldSpinner)
        val orderSpinner = view.findViewById<Spinner>(R.id.sortOrderSpinner)

        val fields = SortField.values().map { it.displayName }
        fieldSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, fields).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        fieldSpinner.setSelection(currentFilter.sortField.ordinal)
        fieldSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentFilter.sortField = SortField.values()[pos]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val orders = listOf("Descending ↓", "Ascending ↑")
        orderSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, orders).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        orderSpinner.setSelection(if (currentFilter.sortOrder == SortOrder.DESC) 0 else 1)
        orderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentFilter.sortOrder = if (pos == 0) SortOrder.DESC else SortOrder.ASC
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupPresets(view: View) {
        val presetSpinner = view.findViewById<Spinner>(R.id.presetSpinner)
        val presetNames = listOf("-- Select Preset --") + FilterPresets.getPresetNames()
        presetSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, presetNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos > 0) {
                    FilterPresets.getPresetByName(presetNames[pos])?.let { preset ->
                        currentFilter.applyPreset(preset)
                        setupCategoryChips(view)
                        view.findViewById<TextInputEditText>(R.id.minSizeInput)
                            .setText(if (preset.minSizeMB > 0) preset.minSizeMB.toInt().toString() else "")
                        view.findViewById<TextInputEditText>(R.id.maxSizeInput).setText("")
                        view.findViewById<TextInputEditText>(R.id.extensionInput).setText("")
                    }
                    presetSpinner.setSelection(0)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupButtons(view: View) {
        view.findViewById<Button>(R.id.btnApply).setOnClickListener {
            // Capture final input values
            currentFilter.minSizeMB = view.findViewById<TextInputEditText>(R.id.minSizeInput)
                .text.toString().toFloatOrNull() ?: 0f
            currentFilter.maxSizeMB = view.findViewById<TextInputEditText>(R.id.maxSizeInput)
                .text.toString().toFloatOrNull() ?: Float.MAX_VALUE
            currentFilter.extensionFilter = view.findViewById<TextInputEditText>(R.id.extensionInput)
                .text.toString()

            // Send result via FragmentResult API (rotation-safe)
            parentFragmentManager.setFragmentResult(
                RESULT_KEY,
                Bundle().apply { putBundle(BUNDLE_FILTER, currentFilter.toBundle()) }
            )
            dismiss()
        }

        view.findViewById<Button>(R.id.btnReset).setOnClickListener {
            currentFilter.reset()
            setupCategoryChips(view)
            setupScopeChips(view)
            view.findViewById<TextInputEditText>(R.id.minSizeInput).setText("")
            view.findViewById<TextInputEditText>(R.id.maxSizeInput).setText("")
            view.findViewById<TextInputEditText>(R.id.extensionInput).setText("")
            view.findViewById<Spinner>(R.id.sortFieldSpinner).setSelection(SortField.RELEVANCE.ordinal)
            view.findViewById<Spinner>(R.id.sortOrderSpinner).setSelection(1)
        }

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dismiss() }
    }
}
