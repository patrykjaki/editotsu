package ani.dantotsu.settings.data

import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.rows.ChoiceOption
import android.app.Activity
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import ani.dantotsu.BuildConfig
import ani.dantotsu.R
import ani.dantotsu.animethemes.ThemeFavourites
import ani.dantotsu.databinding.DialogUserAgentBinding
import ani.dantotsu.settings.saving.internal.BackupSection
import ani.dantotsu.settings.saving.internal.PreferenceKeystore
import ani.dantotsu.settings.saving.internal.PreferencePackager
import ani.dantotsu.settings.saving.internal.wrapBackupEnvelope
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog

/**
 * One backup and restore path for every screen that offers it.
 *
 * File types: `.ani` is the plain JSON envelope; `.sani` is a 16-byte salt
 * followed by the envelope encrypted with the password (same format as the
 * older Settings > Common export, so those files still restore).
 */
object BackupRestore {

    /** Backup file contents for [sections]; encrypted when [password] is set. */
    fun create(sections: Set<BackupSection>, password: CharArray?): ByteArray {
        val envelope = wrapBackupEnvelope(
            PreferencePackager.packSections(sections),
            BuildConfig.VERSION_NAME,
            System.currentTimeMillis()
        )
        if (password == null) return envelope.toByteArray(Charsets.UTF_8)
        val salt = PreferenceKeystore.generateSalt()
        return salt + PreferenceKeystore.encryptWithPassword(password, envelope, salt)
    }

    /** Lets the user pick sections, asks for a password when logins are included. */
    fun chooseSections(activity: Activity, onChosen: (Set<BackupSection>, CharArray?) -> Unit) {
        val options = listOf(BackupSection.Extensions, BackupSection.History, BackupSection.Logins)
        val labels = arrayOf(
            activity.getString(R.string.backup_section_extensions),
            activity.getString(R.string.backup_section_history),
            activity.getString(R.string.backup_section_logins),
        )
        val checked = booleanArrayOf(true, true, false)
        ChoiceSheet.multi(
            activity, activity.getString(R.string.backup_choose_title), labels.map { ChoiceOption(it) }, checked,
            confirm = ChoiceSheet.Confirm(R.string.button_backup, R.drawable.backup_restore) {
                val sections = options.filterIndexed { i, _ -> checked[i] }.toSet() + BackupSection.Settings
                if (BackupSection.Logins in sections) {
                    passwordDialog(activity, exporting = true) { password ->
                        if (password == null) return@passwordDialog
                        passwordDialog(activity, exporting = true, confirming = true) { again ->
                            if (again == null) return@passwordDialog
                            if (password.contentEquals(again)) {
                                onChosen(sections, password)
                            } else {
                                toast(activity.getString(R.string.backup_password_mismatch))
                            }
                            again.fill('0')
                        }
                    }
                } else {
                    onChosen(sections, null)
                }
            },
        )
    }

    /** Restores [bytes] read from a file called [name]; restarts the app process when done. */
    fun restore(activity: Activity, bytes: ByteArray, name: String) {
        when {
            name.endsWith(".sani") -> passwordDialog(activity, exporting = false) { password ->
                if (password == null) return@passwordDialog
                if (bytes.size <= 16) {
                    toast(activity.getString(R.string.backup_invalid_file))
                    return@passwordDialog
                }
                val json = try {
                    PreferenceKeystore.decryptWithPassword(
                        password,
                        bytes.copyOfRange(16, bytes.size),
                        bytes.copyOfRange(0, 16)
                    )
                } catch (e: Exception) {
                    toast(activity.getString(R.string.backup_wrong_password))
                    return@passwordDialog
                }
                apply(activity, json)
            }
            name.endsWith(".ani") || name.endsWith(".json") ->
                apply(activity, bytes.toString(Charsets.UTF_8))
            else -> toast(activity.getString(R.string.unknown_file_type))
        }
    }

    private fun apply(activity: Activity, json: String) {
        val ok = try {
            PreferencePackager.unpack(json)
        } catch (e: Exception) {
            Logger.log("Backup restore failed: ${e.message}")
            false
        }
        if (!ok) {
            toast(activity.getString(R.string.backup_invalid_file))
            return
        }
        ThemeFavourites.reload()
        val missing = PreferencePackager.lastMissingExtensions
        if (missing.isEmpty()) {
            ProcessRestart.restart(activity)
            return
        }
        activity.customAlertDialog().apply {
            setTitle(R.string.backup_missing_extensions_title)
            setMessage(
                activity.getString(R.string.backup_missing_extensions_message) +
                    "\n\n" + missing.joinToString("\n") { "• $it" }
            )
            setPosButton(R.string.ok) { ProcessRestart.restart(activity) }
            setCancelable(false)
            show()
        }
    }

    /**
     * Password prompt. Passwords up to 16 characters are padded with '0' to 16,
     * as the older export did, so its files decrypt with the same password.
     */
    fun passwordDialog(
        activity: Activity,
        exporting: Boolean,
        confirming: Boolean = false,
        callback: (CharArray?) -> Unit,
    ) {
        val dialogView = DialogUserAgentBinding.inflate(activity.layoutInflater)
        val box = dialogView.userAgentTextBox
        box.hint = activity.getString(R.string.label_password)
        box.setSingleLine()
        val dialog = AlertDialog.Builder(activity, R.style.MyPopup)
            .setTitle(
                activity.getString(if (confirming) R.string.backup_password_confirm else R.string.enter_password)
            )
            .setView(dialogView.root)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel) { d, _ ->
                d.dismiss()
                callback(null)
            }.create()

        fun submit() {
            val text = box.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                toast(activity.getString(R.string.password_cannot_be_empty))
                return
            }
            val password = if (text.length <= 16) {
                CharArray(16) { '0' }.also { text.toCharArray(it) }
            } else {
                text.toCharArray()
            }
            dialog.dismiss()
            callback(password)
        }
        box.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        dialogView.subtitle.visibility = View.VISIBLE
        dialogView.subtitle.text = activity.getString(
            if (exporting) R.string.backup_password_hint else R.string.enter_password_to_decrypt_file
        )
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
    }
}
