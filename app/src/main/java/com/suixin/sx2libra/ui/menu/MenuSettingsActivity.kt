package com.suixin.sx2libra.ui.menu

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.suixin.sx2libra.LibraApplication
import com.suixin.sx2libra.R
import com.suixin.sx2libra.ui.system.applySystemBarInsets
import com.suixin.sx2libra.ui.system.enableImmersiveSystemBars
import kotlinx.coroutines.launch

class MenuSettingsActivity : AppCompatActivity() {
    private val viewModel: MenuSettingsViewModel by viewModels {
        (application as LibraApplication).appContainer.viewModelFactory
    }

    private lateinit var adapter: MenuSettingsAdapter
    private var lastPendingDeleteRequest: String? = null
    private var lastError: MenuSettingsError? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveSystemBars()
        setContentView(R.layout.activity_menu_settings)
        findViewById<View>(R.id.menu_settings_root).applySystemBarInsets(top = true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val list = findViewById<RecyclerView>(R.id.menu_list)
        list.applySystemBarInsets(bottom = true)
        adapter = MenuSettingsAdapter(onDelete = { viewModel.requestDelete(it.id) })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = adapter.move(
                viewHolder.bindingAdapterPosition,
                target.bindingAdapterPosition
            )

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewModel.onReorderFinished(adapter.currentMenuIds())
            }
        })
        touchHelper.attachToRecyclerView(list)
        adapter.onDragHandleDown = { holder -> touchHelper.startDrag(holder) }

        findViewById<View>(R.id.menu_add).setOnClickListener { showAddMenuDialog() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishWithResult()
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finishWithResult()
        return true
    }

    private fun render(state: MenuSettingsUiState) {
        if (adapter.currentMenus != state.menus) adapter.submitList(state.menus)
        val pending = state.pendingDelete
        if (pending != null && pending.requestId != lastPendingDeleteRequest) {
            lastPendingDeleteRequest = pending.requestId
            MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Sx2libra_MaterialAlertDialog)
                .setTitle(R.string.menu_delete_title)
                .setMessage(getString(R.string.menu_delete_message, pending.name))
                .setNegativeButton(android.R.string.cancel) { _, _ -> viewModel.cancelDelete() }
                .setPositiveButton(R.string.menu_delete_confirm) { _, _ -> viewModel.confirmDelete() }
                .setOnCancelListener { viewModel.cancelDelete() }
                .show()
        } else if (pending == null) {
            lastPendingDeleteRequest = null
        }

        if (state.error != null && state.error != lastError) {
            lastError = state.error
            Toast.makeText(this, state.error.userMessage(), Toast.LENGTH_SHORT).show()
        } else if (state.error == null) {
            lastError = null
        }
    }

    private fun showAddMenuDialog() {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_add_forum_menu, null)
        val nameInput = content.findViewById<EditText>(R.id.menu_name_input).apply {
            filters = arrayOf(InputFilter.LengthFilter(20))
        }
        val pathInput = content.findViewById<EditText>(R.id.menu_path_input).apply {
            filters = arrayOf(InputFilter.LengthFilter(ForumMenuDialogLimits.MAX_PATH_LENGTH))
        }
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Sx2libra_MaterialAlertDialog)
            .setTitle(R.string.menu_add_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.menu_add_confirm) { _, _ ->
                viewModel.addMenu(nameInput.text.toString(), pathInput.text.toString())
            }
            .show()
    }

    private fun finishWithResult() {
        val state = viewModel.uiState.value
        val data = Intent().putExtra(EXTRA_REVISION, state.currentRevision)
        setResult(if (state.hasChanges) Activity.RESULT_OK else Activity.RESULT_CANCELED, data)
        finish()
    }

    private fun MenuSettingsError.userMessage(): String = when (this) {
        MenuSettingsError.INVALID_NAME -> getString(R.string.menu_error_name)
        MenuSettingsError.INVALID_PATH -> getString(R.string.menu_error_path)
        MenuSettingsError.DUPLICATE_NAME -> getString(R.string.menu_error_duplicate_name)
        MenuSettingsError.DUPLICATE_PATH -> getString(R.string.menu_error_duplicate_path)
        MenuSettingsError.MENU_NOT_FOUND -> getString(R.string.menu_error_not_found)
        MenuSettingsError.LAST_MENU -> getString(R.string.menu_error_last)
        MenuSettingsError.INVALID_ORDER -> getString(R.string.menu_error_order)
        MenuSettingsError.STORAGE -> getString(R.string.menu_error_storage)
    }

    companion object {
        const val EXTRA_REVISION = "forum_menu_revision"
    }
}

private object ForumMenuDialogLimits {
    const val MAX_PATH_LENGTH = 2_048
}
