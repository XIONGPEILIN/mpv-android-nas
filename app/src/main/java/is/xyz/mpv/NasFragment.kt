package `is`.xyz.mpv

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import `is`.xyz.mpv.databinding.DialogNasCredentialsBinding
import `is`.xyz.mpv.databinding.FragmentNasBinding
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthentication
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NasFragment : Fragment(R.layout.fragment_nas) {
    private lateinit var binding: FragmentNasBinding
    private lateinit var adapter: NasAdapter

    private var baseContext: CIFSContext? = null
    private var cifsContext: CIFSContext? = null
    private var currentPath: String? = null
    private var attemptedAutoConnect = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentNasBinding.bind(view)

        adapter = NasAdapter(mutableListOf()) { item -> onItemClick(item) }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.connectButton.setOnClickListener { showCredentialsDialog() }

        val saved = loadSavedSession()
        if (!attemptedAutoConnect && savedInstanceState == null && saved != null) {
            attemptedAutoConnect = true
            binding.connectButton.isEnabled = false
            binding.connectButton.visibility = View.INVISIBLE
            connectToNas(saved.address, saved.username, saved.password, saved.lastPath)
        }

        updateActionBarTitle()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (canGoUp()) {
                        goUp()
                    } else {
                        parentFragmentManager.popBackStack()
                    }
                }
            }
        )
    }

    private fun onItemClick(item: NasItem) {
        if (item.isDirectory) {
            browse(item.smbFile.path)
        } else {
            playFiles(item)
        }
    }

    private fun showCredentialsDialog() {
        val dialogBinding = DialogNasCredentialsBinding.inflate(layoutInflater)
        loadSavedSession()?.let {
            dialogBinding.addressEditText.setText(it.address)
            dialogBinding.usernameEditText.setText(it.username)
            dialogBinding.passwordEditText.setText(it.password)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.nas_credentials_dialog_title))
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_connect) { _, _ ->
                val address = dialogBinding.addressEditText.text.toString().trim()
                val username = dialogBinding.usernameEditText.text.toString()
                val password = dialogBinding.passwordEditText.text.toString()

                if (address.isBlank()) {
                    Toast.makeText(requireContext(), R.string.nas_error_empty_address, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                connectToNas(address, username, password)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun connectToNas(address: String, username: String, password: String, initialPath: String? = null) {
        binding.connectButton.isEnabled = false
        binding.connectButton.visibility = View.INVISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val base = baseContext ?: withContext(Dispatchers.IO) {
                    SingletonContext.getInstance()
                }.also { baseContext = it }

                val context = if (username.isBlank() && password.isBlank()) {
                    base.withAnonymousCredentials()
                } else {
                    base.withCredentials(NtlmPasswordAuthentication(base, null, username, password))
                }
                cifsContext = context

                val targetPath = initialPath?.let { ensureTrailingSlash(it) }
                    ?: ensureTrailingSlash("smb://$address")
                currentPath = targetPath

                saveSession(address, username, password, targetPath)
                browse(targetPath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to NAS", e)
                val message = e.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.nas_error_generic)
                Toast.makeText(requireContext(), getString(R.string.nas_error_browsing, message), Toast.LENGTH_LONG).show()
                binding.connectButton.isEnabled = true
                binding.connectButton.visibility = View.VISIBLE
            }
        }
    }

    private fun browse(path: String) {
        val safeContext = cifsContext ?: run {
            Toast.makeText(requireContext(), R.string.nas_error_generic, Toast.LENGTH_SHORT).show()
            binding.connectButton.visibility = View.VISIBLE
            binding.connectButton.isEnabled = true
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val items: List<NasItem> = withContext(Dispatchers.IO) {
                try {
                    val smbFile = SmbFile(path, safeContext)
                    if (!smbFile.exists()) {
                        throw SmbException("Path not found: $path")
                    }
                    smbFile.listFiles()
                        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        ?.map { NasItem(it.name.trimEnd('/'), it.isDirectory, it) }
                        ?: emptyList()
                } catch (e: Exception) {
                    throw e
                }
            }

            adapter.updateData(items)
            currentPath = path
            updateLastPath(path)
            binding.connectButton.visibility = View.GONE
        }.invokeOnCompletion { throwable ->
            if (throwable != null) {
                Log.e(TAG, "Error browsing NAS", throwable)
                val message = throwable.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.nas_error_generic)
                Toast.makeText(requireContext(), getString(R.string.nas_error_browsing, message), Toast.LENGTH_LONG).show()
                binding.connectButton.visibility = View.VISIBLE
                binding.connectButton.isEnabled = true
            }
        }
    }

    fun canGoUp(): Boolean {
        val path = currentPath ?: return false
        val context = cifsContext ?: return false
        return try {
            val parent = SmbFile(path, context).parent
            parent != null
        } catch (_: Exception) {
            false
        }
    }

    fun goUp() {
        val path = currentPath ?: return
        val context = cifsContext ?: return
        val parentPath = try {
            SmbFile(path, context).parent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate up from $path", e)
            null
        } ?: return

        browse(parentPath)
    }

    private fun playFiles(item: NasItem) {
        val context = cifsContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val playlist: List<String> = withContext(Dispatchers.IO) {
                val path = currentPath ?: return@withContext emptyList()
                val directory = SmbFile(path, context)
                directory.listFiles()
                    ?.filter { file ->
                        val extension = file.name.substringAfterLast('.', "").lowercase()
                        !file.isDirectory && Utils.MEDIA_EXTENSIONS.contains(extension)
                    }
                    ?.sortedBy { it.name.lowercase() }
                    ?.map { it.path }
                    ?: emptyList()
            }

            if (playlist.isEmpty()) {
                Toast.makeText(requireContext(), R.string.nas_error_no_media, Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (!Utils.isNativePlayerAvailable(requireContext())) {
                Toast.makeText(requireContext(), R.string.nas_error_player_missing, Toast.LENGTH_LONG).show()
                return@launch
            }

            val startIndex = playlist.indexOfFirst { it == item.smbFile.path }.takeIf { it >= 0 } ?: 0
            val intent = Intent(requireContext(), MPVActivity::class.java).apply {
                putExtra("filepath", playlist[startIndex])
                putExtra("playlist", playlist.toTypedArray())
                putExtra("playlist-start-index", startIndex)
            }
            startActivity(intent)
        }
    }

    private fun ensureTrailingSlash(path: String): String =
        if (path.endsWith("/")) path else "$path/"

    override fun onResume() {
        super.onResume()
        updateActionBarTitle()
    }

    override fun onDestroyView() {
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.mpv_activity)
        requireActivity().title = getString(R.string.mpv_activity)
        super.onDestroyView()
    }

    private fun updateActionBarTitle() {
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.action_open_nas)
        requireActivity().title = getString(R.string.action_open_nas)
    }

    private fun loadSavedSession(): SavedNasSession? {
        val prefs = prefs()
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val username = prefs.getString(KEY_USERNAME, "") ?: ""
        val password = prefs.getString(KEY_PASSWORD, "") ?: ""
        val lastPath = prefs.getString(KEY_LAST_PATH, null)
        return SavedNasSession(address, username, password, lastPath)
    }

    private fun saveSession(address: String, username: String, password: String, lastPath: String) {
        prefs().edit {
            putString(KEY_ADDRESS, address)
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
            putString(KEY_LAST_PATH, lastPath)
        }
    }

    private fun updateLastPath(path: String) {
        prefs().edit {
            putString(KEY_LAST_PATH, path)
        }
    }

    private fun prefs(): SharedPreferences =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "NasFragment"
        private const val PREFS_NAME = "nas_session"
        private const val KEY_ADDRESS = "address"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_LAST_PATH = "last_path"
    }
}

private data class SavedNasSession(
    val address: String,
    val username: String,
    val password: String,
    val lastPath: String?
)
