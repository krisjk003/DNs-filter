#!/bin/bash
# VPS Setup Script for WireGuard + AdGuard Home
# NOTE: Review and modify variables before execution. Must be run as root.

if [ "$EUID" -ne 0 ]; then
  echo "Please run as root"
  exit 1
fi

echo "Updating system..."
apt update && apt upgrade -y

echo "Installing WireGuard..."
apt install wireguard iptables -y

# Enable IP forwarding
sed -i 's/#net.ipv4.ip_forward=1/net.ipv4.ip_forward=1/' /etc/sysctl.conf
sed -i 's/#net.ipv6.conf.all.forwarding=1/net.ipv6.conf.all.forwarding=1/' /etc/sysctl.conf
sysctl -p

echo "Generating Server Keys..."
cd /etc/wireguard
umask 077
wg genkey | tee server_private.key | wg pubkey > server_public.key
SERVER_PRIV=$(cat server_private.key)
SERVER_PUB=$(cat server_public.key)

echo "Generating Client 1 Keys..."
wg genkey | tee client1_private.key | wg pubkey > client1_public.key
CLIENT1_PRIV=$(cat client1_private.key)
CLIENT1_PUB=$(cat client1_public.key)

# Identify default interface (usually eth0 or ens3)
DEFAULT_IFACE=$(ip route get 8.8.8.8 | grep -Po '(?<=dev )(\S+)' | head -1)
SERVER_PUBLIC_IP=$(curl -s icanhazip.com)

cat << WG_CONF > /etc/wireguard/wg0.conf
[Interface]
Address = 10.66.66.1/24, fd42:42:42::1/64
ListenPort = 51820
PrivateKey = $SERVER_PRIV
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o $DEFAULT_IFACE -j MASQUERADE; ip6tables -A FORWARD -i wg0 -j ACCEPT; ip6tables -t nat -A POSTROUTING -o $DEFAULT_IFACE -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o $DEFAULT_IFACE -j MASQUERADE; ip6tables -D FORWARD -i wg0 -j ACCEPT; ip6tables -t nat -D POSTROUTING -o $DEFAULT_IFACE -j MASQUERADE

[Peer]
PublicKey = $CLIENT1_PUB
AllowedIPs = 10.66.66.2/32, fd42:42:42::2/128
WG_CONF

echo "Starting WireGuard..."
systemctl enable wg-quick@wg0
systemctl start wg-quick@wg0

# Option: Install AdGuard Home for Mode C (Secure Tunnel + Ad Blocking)
echo "Do you want to install AdGuard Home for remote DNS filtering? (y/n)"
read -r INSTALL_ADGUARD
if [ "$INSTALL_ADGUARD" == "y" ]; then
    curl -s -S -L https://raw.githubusercontent.com/AdguardTeam/AdGuardHome/master/scripts/install.sh | sh
    echo "AdGuard Home installed. Configure it on http://$SERVER_PUBLIC_IP:3000 to listen on 10.66.66.1"
    DNS_IP="10.66.66.1"
else
    DNS_IP="1.1.1.1"
fi

cat << CLIENT_CONF > /root/client1.conf
[Interface]
PrivateKey = $CLIENT1_PRIV
Address = 10.66.66.2/32, fd42:42:42::2/128
DNS = $DNS_IP

[Peer]
PublicKey = $SERVER_PUB
Endpoint = $SERVER_PUBLIC_IP:51820
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
CLIENT_CONF

echo "=========================================================="
echo "Setup Complete!"
echo "Your client profile is saved at /root/client1.conf"
echo "Transfer this securely to your Android device to import into ZeroAds."
echo "=========================================================="
