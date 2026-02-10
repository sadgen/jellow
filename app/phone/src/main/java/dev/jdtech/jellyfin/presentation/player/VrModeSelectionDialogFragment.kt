package dev.jdtech.jellyfin.presentation.player

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel

class VrModeSelectionDialogFragment(private val viewModel: PlayerViewModel) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val modes = arrayOf(
            getString(R.string.player_vr_360_2d),
            getString(R.string.player_vr_360_3d_lr),
            getString(R.string.player_vr_360_3d_tb),
            getString(R.string.player_vr_180_2d),
            getString(R.string.player_vr_180_3d_lr),
            getString(R.string.player_vr_plane_2d)
        )
        val modeValues = arrayOf(
            "360_2D",
            "360_3D_LR",
            "360_3D_TB",
            "180_2D",
            "180_3D_LR",
            "PLANE_2D"
        )

        val currentProjection = viewModel.playerVrProjection.value
        val checkedItem = modeValues.indexOf(currentProjection).coerceAtLeast(0)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.player_controls_vr_mode)
            .setSingleChoiceItems(modes, checkedItem) { dialog, which ->
                viewModel.setVrProjection(modeValues[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }
}
