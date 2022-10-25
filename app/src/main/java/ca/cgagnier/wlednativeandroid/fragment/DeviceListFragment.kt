package ca.cgagnier.wlednativeandroid.fragment

import android.os.Bundle
import android.view.*
import androidx.activity.OnBackPressedCallback
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ca.cgagnier.wlednativeandroid.*
import ca.cgagnier.wlednativeandroid.adapter.DeviceListAdapter
import ca.cgagnier.wlednativeandroid.databinding.FragmentDeviceListBinding
import ca.cgagnier.wlednativeandroid.repository.DeviceRepository
import ca.cgagnier.wlednativeandroid.repository.DeviceViewModel
import ca.cgagnier.wlednativeandroid.service.DeviceApi


class DeviceListFragment : Fragment(),
    DeviceRepository.DataChangedListener,
    SwipeRefreshLayout.OnRefreshListener {

    private val deviceViewModel: DeviceViewModel by activityViewModels()

    private var _binding: FragmentDeviceListBinding? = null
    private val binding get() = _binding!!


    private lateinit var deviceListAdapter: DeviceListAdapter
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    override fun onResume() {
        super.onResume()
        DeviceRepository.registerDataChangedListener(this)
        deviceListAdapter.replaceItems(DeviceRepository.getAllNotHidden())
        onRefresh()
    }

    override fun onPause() {
        super.onPause()
        DeviceRepository.unregisterDataChangedListener(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeviceListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val layoutManager = LinearLayoutManager(binding.root.context)

        swipeRefreshLayout = binding.swipeRefresh
        swipeRefreshLayout.setOnRefreshListener(this)

        val slidingPaneLayout = binding.slidingPaneLayout
        slidingPaneLayout.lockMode = SlidingPaneLayout.LOCK_MODE_LOCKED

        setMenu()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            DeviceListOnBackPressedCallback(slidingPaneLayout)
        )

        deviceListAdapter = DeviceListAdapter(DeviceRepository.getAllNotHidden()) {
            if (deviceViewModel.currentDevice.value != it) {
                deviceViewModel.updateCurrentDevice(it)
            }
            deviceListAdapter.isSelectable = !slidingPaneLayout.isSlideable
            binding.slidingPaneLayout.openPane()
        }

        binding.deviceListRecyclerView.adapter = deviceListAdapter
        binding.deviceListRecyclerView.layoutManager = layoutManager
        binding.deviceListRecyclerView.setHasFixedSize(true)

        val emptyDataObserver = EmptyDataObserver(binding.deviceListRecyclerView, binding.emptyDataParent)
        deviceListAdapter.registerAdapterDataObserver(emptyDataObserver)
        deviceListAdapter.isSelectable = !slidingPaneLayout.isSlideable

        binding.emptyDataParent.findMyDeviceButton.setOnClickListener {
            openAddDeviceFragment()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setMenu() {
        (requireActivity() as MenuHost).addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.home, menu)
                val actionBar = activity?.actionBar

                actionBar?.setDisplayHomeAsUpEnabled(true)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_device_add -> {
                        openAddDeviceFragment()
                        true
                    }
                    R.id.action_refresh -> {
                        swipeRefreshLayout.isRefreshing = true
                        onRefresh()
                        true
                    }
                    R.id.action_manage_device -> {
                        openManageDevicesFragment()
                        true
                    }

                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun openAddDeviceFragment() {
        val fragment = DeviceDiscoveryFragment()
        switchContent(R.id.fragment_container_view, fragment)
    }

    private fun openManageDevicesFragment() {
        val fragment = DeviceListManageFragment()
        switchContent(R.id.fragment_container_view, fragment)
    }

    private fun switchContent(id: Int, fragment: Fragment) {
        if (context is MainActivity) {
            val mainActivity = context as MainActivity
            mainActivity.switchContent(id, fragment)
        }
    }

    override fun onItemChanged(item: DeviceItem) {
        deviceListAdapter.itemChanged(item)
        swipeRefreshLayout.isRefreshing = false
    }

    override fun onItemAdded(item: DeviceItem) {
        deviceListAdapter.addItem(item)
    }

    override fun onItemRemoved(item: DeviceItem) {
        deviceListAdapter.removeItem(item)
    }

    // TODO add polling or push or something to automatically update the list
    // Better UX for tablet if data is kept in sync
    override fun onRefresh() {
        for (device in deviceListAdapter.getAllItems()) {
            DeviceApi.update(device)
        }
    }

    inner class DeviceListOnBackPressedCallback(
        private val slidingPaneLayout: SlidingPaneLayout
    ) : OnBackPressedCallback(
        // Set the default 'enabled' state to true only if it is slidable (i.e., the panes
        // are overlapping) and open (i.e., the detail pane is visible).
        slidingPaneLayout.isSlideable && slidingPaneLayout.isOpen
    ), SlidingPaneLayout.PanelSlideListener {

        init {
            slidingPaneLayout.addPanelSlideListener(this)
        }

        override fun handleOnBackPressed() {
            // Return to the list pane when the system back button is pressed.
            slidingPaneLayout.closePane()
        }

        override fun onPanelSlide(panel: View, slideOffset: Float) {}

        override fun onPanelOpened(panel: View) {
            // Intercept the system back button when the detail pane becomes visible.
            isEnabled = true
        }

        override fun onPanelClosed(panel: View) {
            // Disable intercepting the system back button when the user returns to the
            // list pane.
            isEnabled = false
        }
    }

}