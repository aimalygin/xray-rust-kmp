import XrayAppleTunnel

// The whole tunnel lives in xray-rust-mobile: TUN pump, utun fd, tunnel DNS settings and the
// network observer that rebinds Hysteria2/WireGuard. No Kotlin/Native runtime in this process.
final class PacketTunnelProvider: XrayPacketTunnelProvider {}
