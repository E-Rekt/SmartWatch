package com.cztask.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Generic crown-scrollable single-value picker: a title row + one row per
 *  value; tapping a value finishes with EXTRA_RESULT. Reused for reminder
 *  hour/minute and timer durations — a full dial widget is step-3 overkill. */
class PickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val adapter = setUpWearList()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val values = intent.getIntArrayExtra(EXTRA_VALUES) ?: IntArray(0)
        val labels = intent.getStringArrayExtra(EXTRA_LABELS)

        adapter.submit(buildList {
            add(Row.Center(title, dim = true))
            values.forEachIndexed { i, v ->
                add(Row.Center(labels?.getOrNull(i) ?: v.toString(), onTap = {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, v))
                    finish()
                }))
            }
        })
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_VALUES = "values"
        private const val EXTRA_LABELS = "labels"
        const val EXTRA_RESULT = "result"

        fun intent(ctx: Context, title: String, values: IntArray, labels: Array<String>? = null): Intent =
            Intent(ctx, PickerActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_VALUES, values)
                .putExtra(EXTRA_LABELS, labels)

        fun result(data: Intent?): Int? =
            data?.takeIf { it.hasExtra(EXTRA_RESULT) }?.getIntExtra(EXTRA_RESULT, 0)
    }
}
