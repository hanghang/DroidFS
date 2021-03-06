package sushi.hardcore.droidfs

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_create.*
import kotlinx.android.synthetic.main.checkboxes_section.*
import kotlinx.android.synthetic.main.volume_path_section.*
import sushi.hardcore.droidfs.explorers.ExplorerActivity
import sushi.hardcore.droidfs.util.*
import sushi.hardcore.droidfs.widgets.ColoredAlertDialogBuilder
import java.io.File
import java.util.*

class CreateActivity : VolumeActionActivity() {
    companion object {
        private const val PICK_DIRECTORY_REQUEST_CODE = 1
    }
    private var sessionID = -1
    private var isStartingExplorer = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create)
        setupActionBar()
        setupFingerprintStuff()
        edit_password_confirm.setOnEditorActionListener { v, _, _ ->
            onClickCreate(v)
            true
        }
        switch_hidden_volume.setOnClickListener {
            onClickSwitchHiddenVolume(it)
            if (switch_hidden_volume.isChecked){
                ColoredAlertDialogBuilder(this)
                    .setTitle(R.string.warning)
                    .setMessage(R.string.hidden_volume_warning)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    fun pickDirectory(view: View?) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(i, PICK_DIRECTORY_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == PICK_DIRECTORY_REQUEST_CODE) {
                if (data?.data != null) {
                    if (PathUtils.isTreeUriOnPrimaryStorage(data.data!!)){
                        val path = PathUtils.getFullPathFromTreeUri(data.data, this)
                        if (path != null){
                            edit_volume_path.setText(path)
                        } else {
                            ColoredAlertDialogBuilder(this)
                                .setTitle(R.string.error)
                                .setMessage(R.string.path_from_uri_null_error_msg)
                                .setPositiveButton(R.string.ok, null)
                                .show()
                        }
                    } else {
                        ColoredAlertDialogBuilder(this)
                            .setTitle(R.string.warning)
                            .setMessage(R.string.create_on_sdcard_error_msg)
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    }
                }
            }
        }
    }

    fun onClickCreate(view: View?) {
        loadVolumePath {
            val password = edit_password.text.toString().toCharArray()
            val passwordConfirm = edit_password_confirm.text.toString().toCharArray()
            if (!password.contentEquals(passwordConfirm)) {
                Toast.makeText(this, R.string.passwords_mismatch, Toast.LENGTH_SHORT).show()
            } else {
                object: LoadingTask(this, R.string.loading_msg_create){
                    override fun doTask(activity: AppCompatActivity) {
                        val volumeFile = File(currentVolumePath)
                        var goodDirectory = false
                        if (!volumeFile.isDirectory) {
                            if (volumeFile.mkdirs()) {
                                goodDirectory = true
                            } else {
                                stopTask {
                                    ColoredAlertDialogBuilder(activity)
                                        .setTitle(R.string.warning)
                                        .setMessage(R.string.create_cant_write_error_msg)
                                        .setPositiveButton(R.string.ok, null)
                                        .show()
                                }
                            }
                        } else {
                            val dirContent = volumeFile.list()
                            if (dirContent != null){
                                if (dirContent.isEmpty()) {
                                    if (volumeFile.canWrite()){
                                        goodDirectory = true
                                    } else {
                                        stopTask {
                                            ColoredAlertDialogBuilder(activity)
                                                .setTitle(R.string.warning)
                                                .setMessage(R.string.create_cant_write_error_msg)
                                                .setPositiveButton(R.string.ok, null)
                                                .show()
                                        }
                                    }
                                } else {
                                    stopTaskWithToast(R.string.dir_not_empty)
                                }
                            } else {
                                stopTaskWithToast(R.string.listdir_null_error_msg)
                            }
                        }
                        if (goodDirectory) {
                            if (GocryptfsVolume.createVolume(currentVolumePath, password, GocryptfsVolume.ScryptDefaultLogN, ConstValues.creator)) {
                                var returnedHash: ByteArray? = null
                                if (checkbox_save_password.isChecked){
                                    returnedHash = ByteArray(GocryptfsVolume.KeyLen)
                                }
                                sessionID = GocryptfsVolume.init(currentVolumePath, password, null, returnedHash)
                                if (sessionID != -1) {
                                    if (checkbox_remember_path.isChecked) {
                                        if (volumeDatabase.isVolumeSaved(currentVolumeName)) { //cleaning old saved path
                                            volumeDatabase.removeVolume(Volume(currentVolumeName))
                                        }
                                        volumeDatabase.saveVolume(Volume(currentVolumeName, switch_hidden_volume.isChecked))
                                    }
                                    if (checkbox_save_password.isChecked && returnedHash != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
                                        stopTask {
                                            savePasswordHash(returnedHash) {
                                                startExplorer()
                                            }
                                        }
                                    } else {
                                        stopTask { startExplorer() }
                                    }
                                } else {
                                    stopTaskWithToast(R.string.open_volume_failed)
                                }
                            } else {
                                stopTask {
                                    ColoredAlertDialogBuilder(activity)
                                        .setTitle(R.string.error)
                                        .setMessage(R.string.create_volume_failed)
                                        .setPositiveButton(R.string.ok, null)
                                        .show()
                                }
                            }
                        }
                    }
                    override fun doFinally(activity: AppCompatActivity) {
                        Arrays.fill(password, 0.toChar())
                        Arrays.fill(passwordConfirm, 0.toChar())
                    }
                }
            }
        }
    }

    private fun startExplorer(){
        ColoredAlertDialogBuilder(this)
                .setTitle(R.string.success_volume_create)
                .setMessage(R.string.success_volume_create_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val intent = Intent(this, ExplorerActivity::class.java)
                    intent.putExtra("sessionID", sessionID)
                    intent.putExtra("volume_name", File(currentVolumeName).name)
                    startActivity(intent)
                    isStartingExplorer = true
                    finish()
                }
                .show()
    }

    fun onClickRememberPath(view: View) {
        if (!checkbox_remember_path.isChecked) {
            checkbox_save_password.isChecked = false
        }
    }

    override fun onPause() {
        super.onPause()
        //Closing volume if leaving activity while showing dialog
        if (sessionID != -1 && !isStartingExplorer) {
            GocryptfsVolume(sessionID).close()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Wiper.wipeEditText(edit_password)
        Wiper.wipeEditText(edit_password_confirm)
    }
}
