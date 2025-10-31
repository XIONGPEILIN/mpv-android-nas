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
import `is`.xyz.mpv.network.SmbHttpProxy
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthentication
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayList

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
            val listing = withContext(Dispatchers.IO) {
                val path = currentPath ?: return@withContext DirectoryScanResult(emptyList(), emptyList())
                val directory = SmbFile(path, context)
                val entries = directory.listFiles()
                    ?.filter { !it.isDirectory }
                    ?.sortedBy { it.name.lowercase() }
                    ?: emptyList()

                val media = entries.filter { file ->
                    val extension = file.name.substringAfterLast('.', "").lowercase()
                    Utils.MEDIA_EXTENSIONS.contains(extension)
                }
                val subtitles = entries.filter { file ->
                    val extension = file.name.substringAfterLast('.', "").lowercase()
                    Utils.SUBTITLE_EXTENSIONS.contains(extension)
                }
                DirectoryScanResult(media, subtitles)
            }

            val playlist = listing.mediaFiles
            val subtitleFiles = listing.subtitleFiles

            if (playlist.isEmpty()) {
                Toast.makeText(requireContext(), R.string.nas_error_no_media, Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (!Utils.isNativePlayerAvailable(requireContext())) {
                Toast.makeText(requireContext(), R.string.nas_error_player_missing, Toast.LENGTH_LONG).show()
                return@launch
            }

            val subtitleEntries = subtitleFiles.map { file ->
                val name = file.name.trimEnd('/')
                SubtitleProxy(name, SmbHttpProxy.register(context, file.path, name))
            }
            val subtitlesByMedia = mutableMapOf<String, List<String>>()
            val subtitlesToEnable = mutableMapOf<String, List<String>>()

            val startIndex = playlist.indexOfFirst { it.path == item.smbFile.path }.takeIf { it >= 0 } ?: 0
            val proxyEntries = playlist.map { smb ->
                val url = SmbHttpProxy.register(context, smb.path, smb.name.trimEnd('/'))
                val title = smb.name.trimEnd('/')
                if (subtitleEntries.isNotEmpty()) {
                    val matches = subtitleEntries.filter { isSubtitleMatch(title, it.name) }
                    val selected = if (matches.isNotEmpty()) matches else subtitleEntries
                    if (selected.isNotEmpty()) {
                        subtitlesByMedia[url] = selected.map { it.url }
                        matches.firstOrNull()?.let {
                            subtitlesToEnable[url] = listOf(it.url)
                        }
                    }
                }
                ProxyEntry(url, title)
            }
            val intent = Intent(requireContext(), MPVActivity::class.java).apply {
                putExtra("filepath", proxyEntries[startIndex].url)
                putExtra("playlist", proxyEntries.map { it.url }.toTypedArray())
                putExtra("playlist-start-index", startIndex)
                putExtra("playlist-titles", proxyEntries.map { it.title }.toTypedArray())
                putExtra("title", proxyEntries[startIndex].title)

                if (subtitlesByMedia.isNotEmpty()) {
                    val subsBundle = Bundle()
                    subtitlesByMedia.forEach { (key, value) ->
                        if (value.isNotEmpty()) {
                            subsBundle.putStringArrayList(key, ArrayList(value))
                        }
                    }
                    if (!subsBundle.isEmpty) {
                        putExtra("playlist-subs", subsBundle)
                    }
                }
                if (subtitlesToEnable.isNotEmpty()) {
                    val enableBundle = Bundle()
                    subtitlesToEnable.forEach { (key, value) ->
                        if (value.isNotEmpty()) {
                            enableBundle.putStringArrayList(key, ArrayList(value))
                        }
                    }
                    if (!enableBundle.isEmpty) {
                        putExtra("playlist-subs-enable", enableBundle)
                    }
                }
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

private data class ProxyEntry(
    val url: String,
    val title: String
)

private data class DirectoryScanResult(
    val mediaFiles: List<SmbFile>,
    val subtitleFiles: List<SmbFile>
)

private data class SubtitleProxy(
    val name: String,
    val url: String
)

private fun isSubtitleMatch(videoName: String, subtitleName: String): Boolean {
    val videoStem = videoName.substringBeforeLast('.', videoName).lowercase()
    val subtitleStem = subtitleName.substringBeforeLast('.', subtitleName).lowercase()
    if (videoStem == subtitleStem) {
        return true
    }
    if (!subtitleStem.startsWith(videoStem)) {
        return false
    }
    val remainder = subtitleStem.removePrefix(videoStem)
    if (remainder.isEmpty()) {
        return true
    }
    val leading = remainder.first()
    return leading == '.' || leading == '-' || leading == '_' || leading == ' ' || leading == '[' || leading == '('
}
