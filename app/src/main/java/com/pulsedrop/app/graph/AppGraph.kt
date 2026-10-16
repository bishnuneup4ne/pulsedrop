package com.pulsedrop.app.graph

import com.pulsedrop.app.discovery.PeerCapabilityRegistry

/**
 * Composition root for the cross-cutting singletons the features share.
 *
 * Deliberately not a DI framework: this app is one module with a handful of long-lived
 * collaborators, and an object with typed properties keeps the wiring readable. Features
 * constructed before the phase rewrite reaches them keep building their own collaborators
 * and graduate here one phase at a time.
 */
object AppGraph {

    /** Merged view of what every reachable peer can do; see docs/Protocol-V2.md. */
    val peerCaps = PeerCapabilityRegistry()
}
