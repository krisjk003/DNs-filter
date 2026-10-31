# Architecture Decision Record: DNS and WireGuard Integration

## Context
ZeroAds currently implements local DNS filtering by establishing an Android `VpnService`, reading raw packets from the TUN interface, parsing UDP Port 53 packets in Java, and dropping/rewriting ad domains while forwarding others via a protected socket. 
The new objective requires adding a full-device encrypted WireGuard tunnel, supporting three modes:
- **Mode A: DNS Protection** (Local only)
- **Mode B: Secure Tunnel** (Full WireGuard, no specific filtering)
- **Mode C: Secure Tunnel + Ad Blocking** (Full WireGuard + DNS filtering)

## Decision
We elected to implement the **Alternative Architecture: Full-tunnel WireGuard with a Remote DNS Filtering Resolver**, alongside a **VpnCoordinator** to cleanly switch between the existing local DNS engine and the official WireGuard engine.

### Why?
1. **Constraint adherence**: The official `wireguard-android` (`com.wireguard.android:tunnel`) dependency consumes the `VpnService` TUN file descriptor entirely within its native `GoBackend` implementation. Exposing a Java-level packet processing hook between the TUN interface and the Go encryption layer would require heavily modifying and recompiling the WireGuard native core. This violates the directive to avoid modifying WireGuard's cryptographic implementation and introduces severe maintenance complexity.
2. **DNS Leak Prevention**: In a full-tunnel mode, all traffic (including DNS) must securely route through the encrypted WireGuard UDP tunnel. Attempting to split DNS out of the tunnel locally would leak DNS requests to the local ISP, breaking privacy.
3. **Preservation of Existing Code**: By keeping `AdBlockVpnService` untouched, we guarantee that Mode A remains byte-for-byte identical to its baseline.

### Implementation Details
- **Mode A**: Starts the existing `AdBlockVpnService`. Packets are filtered locally on the device.
- **Mode B**: Starts the `WireGuard` backend. DNS requests are sent to the standard DNS specified in the WireGuard profile (e.g., `1.1.1.1`), routed securely through the VPS.
- **Mode C**: Starts the `WireGuard` backend, but the user provisions their VPS with a DNS filtering resolver (like AdGuard Home or Pi-Hole). The WireGuard `.conf` profile imported by the user specifies the VPS's internal tunnel IP (e.g., `10.66.66.1`) as the DNS server. ZeroAds UI accurately displays that filtering is occurring remotely on the VPS.

This safely achieves the goal without breaking Android constraints (one active VPN interface) or introducing security vulnerabilities in the WireGuard stack.
