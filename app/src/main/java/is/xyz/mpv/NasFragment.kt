package `is`.xyz.mpv

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import `is`.xyz.mpv.databinding.FragmentNasBinding

class NasFragment : Fragment(R.layout.fragment_nas) {
    private lateinit var binding: FragmentNasBinding

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentNasBinding.bind(view)

        // TODO: Implement NAS browsing logic
    }
}
