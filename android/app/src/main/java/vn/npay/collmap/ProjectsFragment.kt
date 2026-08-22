package vn.npay.collmap

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment

/**
 * The Project destination: the list, with a project's own screen pushed on top
 * of it.
 *
 * Both live in this fragment's child manager rather than in the activity's, so
 * opening a project never touches the bottom bar or the other two tabs -- and
 * back inside this tab means "back to the list", not "leave the app".
 */
class ProjectsFragment : Fragment(R.layout.fragment_projects) {

    override fun onViewCreated(view: View, state: Bundle?) {
        if (childFragmentManager.findFragmentById(R.id.projects_container) == null) {
            childFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.projects_container, ProjectListFragment())
                .commit()
        }

        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                childFragmentManager.popBackStack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        childFragmentManager.addOnBackStackChangedListener {
            back.isEnabled = childFragmentManager.backStackEntryCount > 0
        }
        back.isEnabled = childFragmentManager.backStackEntryCount > 0
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) return
        (childFragmentManager.findFragmentById(R.id.projects_container)
            as? ProjectListFragment)?.refreshIfConnected()
    }

    fun openDetail(server: String, project: String) {
        childFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.projects_container,
                ProjectDetailFragment.create(server, project))
            .addToBackStack(null)
            .commit()
    }
}
