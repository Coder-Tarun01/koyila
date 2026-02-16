package com.sonicsync.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress

class DiscoveryManager(context: Context, private val listener: DiscoveryListener) {

    interface DiscoveryListener {
        fun onServiceFound(serviceInfo: NsdServiceInfo)
        fun onServiceLost(serviceInfo: NsdServiceInfo)
        fun onDiscoveryStopped()
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceName = "SonicSyncHost-${android.os.Build.MODEL}"
    private val serviceType = "_sonicsync._tcp."
    
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false

    fun registerService(port: Int) {
        // Tear down any existing registration
        unregisterService()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = this@DiscoveryManager.serviceName
            serviceType = this@DiscoveryManager.serviceType
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d("DiscoveryManager", "Service registered: ${NsdServiceInfo.serviceName}")
                // Save the assigned name in case of conflict
                // mServiceName = NsdServiceInfo.serviceName
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("DiscoveryManager", "Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d("DiscoveryManager", "Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("DiscoveryManager", "Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("DiscoveryManager", "Error registering service", e)
        }
    }

    fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.e("DiscoveryManager", "Error unregistering service", e)
            }
            registrationListener = null
        }
    }

    fun startDiscovery() {
        if (isDiscovering) {
            stopDiscovery()
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("DiscoveryManager", "Service discovery started")
                isDiscovering = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("DiscoveryManager", "Service found: $service")
                if (service.serviceType == serviceType) {
                    if (service.serviceName.contains(serviceName)) {
                        // It's our own service, ignore or handle specially
                        Log.d("DiscoveryManager", "Found self")
                    }
                    
                    // Resolve IP
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e("DiscoveryManager", "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            Log.d("DiscoveryManager", "Resolve Succeeded: $serviceInfo")
                            // Check for self again with resolved info if needed
                             // if (serviceInfo.serviceName == mServiceName) return;
                            listener.onServiceFound(serviceInfo)
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e("DiscoveryManager", "service lost: $service")
                listener.onServiceLost(service)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i("DiscoveryManager", "Discovery stopped: $serviceType")
                isDiscovering = false
                listener.onDiscoveryStopped()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("DiscoveryManager", "Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("DiscoveryManager", "Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e("DiscoveryManager", "Error starting discovery", e)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e("DiscoveryManager", "Error stopping discovery", e)
            }
            discoveryListener = null
        }
    }
}
