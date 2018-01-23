package net.syncthing.lite.activities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.databinding.DataBindingUtil
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.View
import android.widget.Toast
import net.syncthing.java.bep.IndexBrowser
import net.syncthing.java.core.beans.FileInfo
import net.syncthing.java.core.utils.PathUtils
import net.syncthing.lite.R
import net.syncthing.lite.adapters.FolderContentsAdapter
import net.syncthing.lite.databinding.ActivityFolderBrowserBinding
import net.syncthing.lite.library.DownloadFileTask
import net.syncthing.lite.library.UploadFileTask
import org.jetbrains.anko.intentFor

class FolderBrowserActivity : SyncthingActivity() {

    companion object {

        private val TAG = "FolderBrowserActivity"
        private val REQUEST_WRITE_STORAGE = 142
        private val REQUEST_SELECT_UPLOAD_FILE = 171

        val EXTRA_FOLDER_NAME = "folder_name"
    }

    private lateinit var binding: ActivityFolderBrowserBinding
    private lateinit var indexBrowser: IndexBrowser
    private lateinit var adapter: FolderContentsAdapter
    private var runWhenPermissionsReceived: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_folder_browser)
        binding.mainListViewUploadHereButton.setOnClickListener { showUploadHereDialog() }
        adapter = FolderContentsAdapter(this)
        binding.listView.adapter = adapter
        binding.listView.setOnItemClickListener { _, _, position, _ ->
            val fileInfo = binding.listView.getItemAtPosition(position) as FileInfo
            navigateToFolder(fileInfo)
        }
        val folder = intent.getStringExtra(EXTRA_FOLDER_NAME)
        libraryHandler?.syncthingClient {
            indexBrowser = it.indexHandler.newIndexBrowser(folder, true, true)
            indexBrowser.setOnFolderChangedListener(this::onFolderChanged)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Thread {
            indexBrowser.setOnFolderChangedListener(null)
            indexBrowser.close()
        }.start()
    }

    override fun onBackPressed() {
        val listView = binding.listView
        //click item '0', ie '..' (go to parent)
        listView.performItemClick(adapter.getView(0, null, listView), 0, listView.getItemIdAtPosition(0))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode == REQUEST_SELECT_UPLOAD_FILE && resultCode == Activity.RESULT_OK) {
            libraryHandler?.syncthingClient { syncthingClient ->
                UploadFileTask(this@FolderBrowserActivity, syncthingClient, intent!!.data,
                        indexBrowser.folder, indexBrowser.currentPath,
                        this@FolderBrowserActivity::showUploadHereDialog).uploadFile()
            }
        }
    }

    private fun showFolderListView(path: String) {
        indexBrowser.navigateToNearestPath(path)
        navigateToFolder(indexBrowser.currentPathInfo())
    }

    private fun navigateToFolder(fileInfo: FileInfo) {
        Log.d(TAG, "navigate to path = '" + fileInfo.path + "' from path = '" + indexBrowser.currentPath + "'")
        if (indexBrowser.isRoot() && PathUtils.isParent(fileInfo.path)) {
            finish()
        } else {
            if (fileInfo.isDirectory()) {
                indexBrowser.navigateTo(fileInfo)
                Log.d(TAG, "load folder cache bg")
                binding.listView.visibility = View.GONE
                binding.progressBar.visibility = View.VISIBLE
            } else {
                Log.i(TAG, "pulling file = " + fileInfo)
                executeWithPermissions(
                        Runnable { libraryHandler?.syncthingClient { DownloadFileTask(this, it, fileInfo).downloadFile() } })
            }
        }
    }

    private fun onFolderChanged() {
        runOnUiThread {
            binding.progressBar.visibility = View.GONE
            binding.listView.visibility = View.VISIBLE
            val list = indexBrowser.listFiles()
            Log.i("navigateToFolder", "list for path = '" + indexBrowser.currentPath + "' list = " + list.size + " records")
            Log.d("navigateToFolder", "list for path = '" + indexBrowser.currentPath + "' list = " + list)
            assert(!list.isEmpty())//list must contain at least the 'parent' path
            adapter.clear()
            adapter.addAll(list)
            adapter.notifyDataSetChanged()
            binding.listView.setSelection(0)
            if (indexBrowser.isRoot())
                libraryHandler?.folderBrowser {
                    supportActionBar?.title = it.getFolderInfo(indexBrowser.folder)?.label
                }
            else
                supportActionBar?.title = indexBrowser.currentPathInfo().fileName
        }
}

    private fun updateFolderListView() {
        showFolderListView(indexBrowser.currentPath)
    }

    private fun showUploadHereDialog() {
        executeWithPermissions(Runnable {
            startActivityForResult(intentFor<FilePickerActivity>(), REQUEST_SELECT_UPLOAD_FILE)
        })
    }

    override fun onIndexUpdateProgress(folder: String, percentage: Int) {
        binding.indexUpdate.visibility = View.VISIBLE
        binding.indexUpdateLabel.text = getString(R.string.index_update_progress_label, folder, percentage)
        updateFolderListView()
    }

    override fun onIndexUpdateComplete() {
        binding.indexUpdate.visibility = View.GONE
        updateFolderListView()
    }

    private fun executeWithPermissions(runnable: Runnable) {
        val permissionState = ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (permissionState != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_WRITE_STORAGE)
            runWhenPermissionsReceived = runnable
        } else {
            runnable.run()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        when (requestCode) {
            REQUEST_WRITE_STORAGE -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.toast_write_storage_permission_required,
                            Toast.LENGTH_LONG).show()
                } else {
                    runWhenPermissionsReceived!!.run()
                }
                runWhenPermissionsReceived = null
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }
}
