package ca.cgagnier.wlednativeandroid.fragment

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ca.cgagnier.wlednativeandroid.*
import ca.cgagnier.wlednativeandroid.adapter.DeviceListAdapter
import ca.cgagnier.wlednativeandroid.adapter.RecyclerViewAnimator
import ca.cgagnier.wlednativeandroid.databinding.FragmentDeviceListBinding
import ca.cgagnier.wlednativeandroid.model.Device
import ca.cgagnier.wlednativeandroid.DevicesApplication
import ca.cgagnier.wlednativeandroid.service.DeviceApi
import ca.cgagnier.wlednativeandroid.service.DeviceDiscovery
import ca.cgagnier.wlednativeandroid.viewmodel.DeviceListViewModel
import ca.cgagnier.wlednativeandroid.viewmodel.DeviceListViewModelFactory
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase


class DeviceListFragment : Fragment(),
    SwipeRefreshLayout.OnRefreshListener {

    private val deviceListViewModel: DeviceListViewModel by activityViewModels {
        DeviceListViewModelFactory(
            (requireActivity().application as DevicesApplication).repository,
            (requireActivity().application as DevicesApplication).userPreferencesRepository)
    }

    private var _binding: FragmentDeviceListBinding? = null
    private val binding get() = _binding!!
    private var layoutChangedListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private val loopHandler = Handler(Looper.getMainLooper())

    private lateinit var deviceListAdapter: DeviceListAdapter
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    override fun onResume() {
        super.onResume()
        refreshListFromApi(false)
        checkIfConnectedInAPMode(true)

        Log.i(TAG, "Starting Refresh timer")
        refreshTimer(loopHandler, 5000)
    }

    override fun onPause() {
        Log.i(TAG, "Stopping Refresh timer")
        loopHandler.removeCallbacksAndMessages(null)
        super.onPause()
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

        binding.mainToolbar.setNavigationOnClickListener {
            binding.drawerLayout.open()
        }
        setMenu(binding.mainToolbar)
        setNavigationMenu(binding.navigationView, binding.drawerLayout)

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainToolbar) { insetView, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            insetView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
            windowInsets
        }

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            // This weird workaround of calling setOnApplyWindowInsetsListener on the navigationView
            // is needed to make sure the DeviceViewFragment's insets are set properly on devices
            // with API < 30
            ViewCompat.setOnApplyWindowInsetsListener(binding.navigationView) { _, windowInsets ->
                windowInsets
            }
        }

        swipeRefreshLayout = binding.swipeRefresh
        swipeRefreshLayout.setOnRefreshListener(this)

        val slidingPaneLayout = binding.slidingPaneLayout
        slidingPaneLayout.lockMode = SlidingPaneLayout.LOCK_MODE_LOCKED



        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            DeviceListOnBackPressedCallback(slidingPaneLayout)
        )

        deviceListAdapter = DeviceListAdapter { device: Device ->
            openDevice(device)
        }


        binding.deviceListRecyclerView.adapter = deviceListAdapter
        binding.deviceListRecyclerView.layoutManager = layoutManager
        binding.deviceListRecyclerView.setHasFixedSize(true)
        binding.deviceListRecyclerView.itemAnimator = RecyclerViewAnimator()

        deviceListViewModel.allDevices.observe(viewLifecycleOwner) { devices ->
            devices?.let {
                deviceListAdapter.submitList(it)
            }
            swipeRefreshLayout.isRefreshing = false
            val isEmpty = devices?.isEmpty() == true
            binding.emptyDataParent.layout.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.deviceListRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.swipeRefresh.isEnabled = !isEmpty
        }

        deviceListAdapter.isSelectable = false

        var duringSetup = true
        val activeDeviceObserver = Observer<Device?> {
            if (it != null && it.address == DeviceDiscovery.DEFAULT_WLED_AP_IP) {
                duringSetup = false
            }
            if (!duringSetup && it != null && deviceListViewModel.expectDeviceChange) {
                slidingPaneLayout.openPane()
            }
            duringSetup = false
            if (it != null) {
                binding.deviceListRecyclerView.scrollToPosition(
                    deviceListAdapter.setSelectedDevice(it)
                )
            }
        }
        deviceListViewModel.activeDevice.observe(viewLifecycleOwner, activeDeviceObserver)

        binding.emptyDataParent.findMyDeviceButton.setOnClickListener {
            openAddDeviceFragment()
        }

        binding.apModeContainer.setOnClickListener {
            val device = DeviceDiscovery.getDefaultAPDevice()
            openDevice(device)
        }

        layoutChangedListener = ViewTreeObserver.OnGlobalLayoutListener {
            deviceListAdapter.isSelectable = !slidingPaneLayout.isSlideable
            deviceListViewModel.isTwoPane.value = deviceListAdapter.isSelectable
            view.viewTreeObserver.removeOnGlobalLayoutListener(layoutChangedListener)
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(layoutChangedListener)
    }

    private fun refreshTimer(handler: Handler, delay: Long) {
        Log.i(TAG, "Refreshing devices from timer")
        refreshListFromApi(true)
        handler.postDelayed({refreshTimer(handler, delay)}, delay)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setMenu(toolbar: MaterialToolbar) {
        toolbar.addMenuProvider(object : MenuProvider {
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
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun setNavigationMenu(navigationView: NavigationView, drawerLayout: DrawerLayout) {
        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_device_add -> {
                    openAddDeviceFragment()
                }
                R.id.action_refresh -> {
                    if (deviceListAdapter.itemCount > 0) {
                        swipeRefreshLayout.isRefreshing = true
                    }
                    onRefresh()
                }
                R.id.action_manage_device -> {
                    openManageDevicesFragment()
                }
                R.id.action_settings -> {
                    openSettings()
                }
            }
            drawerLayout.close()
            true
        }
    }

    private fun openAddDeviceFragment() {
        val dialog = DiscoverDeviceFragment()
        dialog.showsDialog = true
        dialog.show(childFragmentManager, "device_discovery")
    }

    private fun openManageDevicesFragment() {
        val dialog = ManageDeviceFragment()
        dialog.showsDialog = true
        dialog.show(childFragmentManager, "device_manage")
    }

    private fun openSettings() {
        val dialog = SettingsFragment()
        dialog.showsDialog = true
        dialog.show(childFragmentManager, "device_manage")
    }

    private fun openDevice(device: Device) {
        Log.i(TAG, "Opening device ${device.address}")
        deviceListViewModel.updateActiveDevice(device)

        deviceListAdapter.isSelectable = !binding.slidingPaneLayout.isSlideable
        deviceListViewModel.isTwoPane.value = deviceListAdapter.isSelectable
        binding.slidingPaneLayout.openPane()
        deviceListViewModel.doRefreshWeb.value = true
    }

    override fun onRefresh() {
        (requireActivity() as AutoDiscoveryActivity).startAutoDiscovery()
        refreshListFromApi(false)
        checkIfConnectedInAPMode()
    }

    private fun refreshListFromApi(silentUpdate: Boolean) {
        if (deviceListViewModel.allDevices.value != null) {
            for (device in deviceListViewModel.allDevices.value!!) {
                DeviceApi.update(device, silentUpdate)
            }
        }
    }

    private fun checkIfConnectedInAPMode(openDevice: Boolean = false) {
        Log.i(TAG, "Checking if connected to AP mode")
        var isConnectedToWledAP: Boolean
        try {
            isConnectedToWledAP = DeviceDiscovery.isConnectedToWledAP(requireContext())
        } catch (e: Exception) {
            isConnectedToWledAP = false
            Log.e(TAG, "Error in checkIfConnectedInAPMode: " + e.message, e)
            Firebase.crashlytics.recordException(e)
        }

        binding.apModeContainer.visibility = if (isConnectedToWledAP) View.VISIBLE else View.GONE

        if (isConnectedToWledAP) {
            Log.i(TAG, "Device is in AP Mode!")

            val connectionManager =
                requireContext().getSystemService(AppCompatActivity.CONNECTIVITY_SERVICE) as ConnectivityManager?

            val request = NetworkRequest.Builder()
            request.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)

            connectionManager!!.requestNetwork(
                request.build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        try {
                            connectionManager.bindProcessToNetwork(network)
                            if (openDevice) {
                                val device = DeviceDiscovery.getDefaultAPDevice()
                                deviceListViewModel.updateActiveDevice(device)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Firebase.crashlytics.recordException(e)
                        }
                    }
                })
        }
    }

    inner class DeviceListOnBackPressedCallback(
        private val slidingPaneLayout: SlidingPaneLayout
    ) : OnBackPressedCallback(
        // Set the default 'enabled' state to true only if it is slidable (i.e., the panes
        // are overlapping) and open (i.e., the detail pane is visible).
        slidingPaneLayout.isOpen
    ), SlidingPaneLayout.PanelSlideListener {

        init {
            slidingPaneLayout.addPanelSlideListener(this)
        }

        override fun handleOnBackPressed() {
            // Return to the list pane when the system back button is pressed.
            if (slidingPaneLayout.isOpen && slidingPaneLayout.isSlideable) {
                slidingPaneLayout.closePane()
                refreshListFromApi(false)
                return
            }

            isEnabled = false
            requireActivity().onBackPressedDispatcher.onBackPressed()
            isEnabled = slidingPaneLayout.isOpen
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

    companion object {
        const val TAG = "DeviceListFragment"
    }
}